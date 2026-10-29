#!/usr/bin/env python3
"""
AUT-0 sandbox only: a Keycloak 26 *simulator* (subset) for testing the INT-3.7 scripts.

It imports nexora-realm.json like `start-dev --import-realm` (with ${ENV:default} substitution from the given .env),
issues RS256 tokens with the same protocol-mapper semantics used by the realm (audience, user attribute with group
inheritance, realm role mapper with composite expansion, hardcoded claim, sub) and implements the admin REST
endpoints the scripts use. Unknown endpoints answer 404 and are logged, so gaps are visible.
It is NOT Keycloak: behaviours not modelled here must still be verified on the real one (AUT-2 / V2).
"""
import base64, copy, json, os, re, sys, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

REALM_FILE, ENV_FILE, PORT, LOG = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
BASE = f"http://localhost:{PORT}"
LOCK = threading.RLock()


def read_env(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$", line)
        if m:
            out[m.group(1)] = m.group(2).strip('"').strip("'")
    return out


ENV = read_env(ENV_FILE)


def subst(text):
    def rep(m):
        name, _, default = m.group(1).partition(":")
        return ENV.get(name, default)
    return re.sub(r"\$\{([^}]+)\}", rep, text)


KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
KID = "sim-" + uuid.uuid4().hex[:8]


def b64u(n):
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


PUB = KEY.public_key().public_numbers()
JWKS = {"keys": [{"kty": "RSA", "kid": KID, "use": "sig", "alg": "RS256", "n": b64u(PUB.n), "e": b64u(PUB.e)}]}

# ------------------------------------------------------------------------------------------------ realm model

R = json.loads(subst(open(REALM_FILE, encoding="utf-8").read()))
REALM = R["realm"]
roles = {}          # name -> rep
scopes = {}         # id -> rep
clients = {}        # id -> rep (+ "_default", "_optional" scope id lists)
groups = {}         # id -> rep
users = {}          # id -> rep (+ "_password", "_roles" set, "_groups" set)


def new_id():
    return str(uuid.uuid4())


def add_role(name, description="", composites=None, attributes=None):
    roles[name] = {"id": new_id(), "name": name, "description": description, "composite": bool(composites),
                   "clientRole": False, "containerId": REALM, "attributes": attributes or {},
                   "_composites": list(composites or [])}


add_role("offline_access"); add_role("uma_authorization")
add_role(f"default-roles-{REALM}", composites=["offline_access", "uma_authorization"])
for r in R.get("roles", {}).get("realm", []):
    add_role(r["name"], r.get("description", ""), attributes=r.get("attributes"))


def mapper(name, kind, config):
    return {"id": new_id(), "name": name, "protocol": "openid-connect", "protocolMapper": kind, "config": config}


def add_scope(name, mappers, attributes=None):
    sid = new_id()
    scopes[sid] = {"id": sid, "name": name, "protocol": "openid-connect", "attributes": attributes or {},
                   "protocolMappers": mappers}
    return sid


# built-in scopes (simplified)
add_scope("basic", [mapper("sub", "oidc-sub-mapper", {"access.token.claim": "true"})])
add_scope("roles", [mapper("realm roles", "oidc-usermodel-realm-role-mapper",
                           {"claim.name": "realm_access.roles", "multivalued": "true", "access.token.claim": "true"}),
                    mapper("audience resolve", "oidc-audience-resolve-mapper", {})])
for n in ["profile", "email", "web-origins", "acr", "offline_access", "address", "phone", "microprofile-jwt",
          "organization"]:
    add_scope(n, [])
add_scope("service_account", [mapper("Client ID", "oidc-usersessionmodel-note-mapper",
                                     {"claim.name": "client_id", "user.session.note": "client_id",
                                      "access.token.claim": "true"})])
for s in R.get("clientScopes", []):
    add_scope(s["name"], [mapper(m["name"], m["protocolMapper"], m.get("config", {})) for m in s.get("protocolMappers", [])],
              s.get("attributes"))


# SIM_SHARED_REPORTS_AUD=1 reproduces the user's realm: an audience mapper nexora-reports added by hand to the
# shared scope nexora-semantic (config copied from the inventory of 29 Oct 2026, not in nexora-realm.json).
if os.environ.get("SIM_SHARED_REPORTS_AUD") == "1":
    for _s in scopes.values():
        if _s["name"] == "nexora-semantic":
            _s["protocolMappers"].insert(0, mapper("nexora-reports-audience", "oidc-audience-mapper", {
                "id.token.claim": "false", "lightweight.claim": "false", "access.token.claim": "true",
                "introspection.token.claim": "true", "included.custom.audience": "nexora-reports"}))


def scope_id(name):
    return next((k for k, v in scopes.items() if v["name"] == name), None)


REALM_DEFAULT = ["profile", "email", "roles", "web-origins", "acr", "basic"]
REALM_OPTIONAL = ["offline_access", "address", "phone", "microprofile-jwt", "organization"]


def add_client(rep, imported=False):
    cid = new_id()
    c = {"id": cid, "clientId": rep["clientId"], "name": rep.get("name", ""), "enabled": rep.get("enabled", True),
         "publicClient": rep.get("publicClient", False), "bearerOnly": rep.get("bearerOnly", False),
         "serviceAccountsEnabled": bool(rep.get("serviceAccountsEnabled", False)),
         "standardFlowEnabled": rep.get("standardFlowEnabled", True),
         "implicitFlowEnabled": bool(rep.get("implicitFlowEnabled", False)),
         "directAccessGrantsEnabled": bool(rep.get("directAccessGrantsEnabled", False)),
         "clientAuthenticatorType": rep.get("clientAuthenticatorType", "client-secret"),
         "secret": rep.get("secret") or (None if rep.get("publicClient") else uuid.uuid4().hex),
         "fullScopeAllowed": rep.get("fullScopeAllowed", True), "protocol": "openid-connect",
         "attributes": dict(rep.get("attributes") or {}), "redirectUris": rep.get("redirectUris", []),
         "protocolMappers": [mapper(m["name"], m["protocolMapper"], m.get("config", {})) for m in rep.get("protocolMappers", [])]}
    if imported and rep.get("defaultClientScopes") is not None:
        c["_default"] = [scope_id(n) for n in rep["defaultClientScopes"] if scope_id(n)]
    else:
        c["_default"] = [scope_id(n) for n in REALM_DEFAULT if scope_id(n)]
        if c["serviceAccountsEnabled"]:
            c["_default"].append(scope_id("service_account"))
    c["_optional"] = [] if imported and rep.get("defaultClientScopes") is not None else [scope_id(n) for n in REALM_OPTIONAL]
    clients[cid] = c
    if c["serviceAccountsEnabled"] and not imported:
        add_user({"username": f"service-account-{c['clientId']}", "enabled": True}, sa_client=c["clientId"],
                 default_roles=True)
    return c


def add_user(rep, sa_client=None, default_roles=True, password=None):
    uid = new_id()
    u = {"id": uid, "username": rep["username"].lower(), "enabled": rep.get("enabled", True),
         "emailVerified": rep.get("emailVerified", False), "firstName": rep.get("firstName"),
         "lastName": rep.get("lastName"), "email": rep.get("email"), "attributes": rep.get("attributes") or {},
         "createdTimestamp": int(time.time() * 1000), "_password": password, "_roles": set(), "_groups": set()}
    if sa_client:
        u["serviceAccountClientId"] = sa_client
    if default_roles:
        u["_roles"].add(f"default-roles-{REALM}")
    users[uid] = u
    return u


for g in R.get("groups", []):
    gid = new_id()
    groups[gid] = {"id": gid, "name": g["name"], "path": "/" + g["name"], "attributes": g.get("attributes", {}),
                   "subGroupCount": 0, "subGroups": []}
for c in R.get("clients", []):
    add_client(c, imported=True)
for ur in R.get("users", []):
    pw = next((cr["value"] for cr in ur.get("credentials", []) if cr.get("type") == "password"), None)
    u = add_user(ur, sa_client=ur.get("serviceAccountClientId"), default_roles=True, password=pw)
    u["_roles"].update(ur.get("realmRoles", []))
    for gp in ur.get("groups", []):
        gid = next(k for k, v in groups.items() if v["path"] == gp)
        u["_groups"].add(gid)

ADMIN_PASSWORD = ENV.get("KC_BOOTSTRAP_ADMIN_PASSWORD")
MASTER_ADMIN_ID = new_id()
import os as _os
FAULTS = {f: True for f in _os.environ.get("SIM_FAULTS", "").split(",") if f}

# ------------------------------------------------------------------------------------------------ tokens


def effective_roles(u):
    out, todo = set(), list(u["_roles"])
    while todo:
        r = todo.pop()
        if r in out or r not in roles:
            continue
        out.add(r)
        todo.extend(roles[r]["_composites"])
    return out


def user_attr(u, name):
    if name in u["attributes"]:
        return u["attributes"][name]
    for gid in u["_groups"]:
        if name in groups[gid]["attributes"]:
            return groups[gid]["attributes"][name]
    return None


def set_claim(claims, path, value):
    parts = path.split(".")
    cur = claims
    for p in parts[:-1]:
        cur = cur.setdefault(p, {})
    cur[parts[-1]] = value


def issue(client, user, issuer):
    now = int(time.time())
    claims = {"exp": now + 900, "iat": now, "jti": new_id(), "iss": issuer, "typ": "Bearer", "azp": client["clientId"]}
    aud = []
    mappers = []
    for sid in client["_default"]:
        mappers.extend(scopes[sid]["protocolMappers"])
    mappers.extend(client["protocolMappers"])
    for m in mappers:
        cfg, kind = m["config"], m["protocolMapper"]
        if kind == "oidc-sub-mapper":
            claims["sub"] = user["id"]
        elif kind == "oidc-audience-mapper" and cfg.get("access.token.claim", "true") != "false":
            aud.append(cfg["included.custom.audience"])
        elif kind == "oidc-audience-resolve-mapper":
            if f"default-roles-{REALM}" in user["_roles"]:
                aud.append("account")
        elif kind == "oidc-usermodel-attribute-mapper" and cfg.get("access.token.claim") == "true":
            v = user_attr(user, cfg["user.attribute"])
            if v is not None:
                set_claim(claims, cfg["claim.name"], v if cfg.get("multivalued") == "true" else v[0] if isinstance(v, list) else v)
        elif kind == "oidc-usermodel-realm-role-mapper" and cfg.get("access.token.claim") == "true":
            set_claim(claims, cfg["claim.name"], sorted(effective_roles(user)))
        elif kind == "oidc-hardcoded-claim-mapper" and cfg.get("access.token.claim") == "true":
            set_claim(claims, cfg["claim.name"], cfg["claim.value"])
        elif kind == "oidc-usersessionmodel-note-mapper" and cfg.get("claim.name") == "client_id":
            claims["client_id"] = client["clientId"]
    aud = list(dict.fromkeys(aud))
    if aud:
        claims["aud"] = aud[0] if len(aud) == 1 else aud
    return jwt.encode(claims, KEY, algorithm="RS256", headers={"kid": KID})


def verify_admin(header):
    if not header or not header.startswith("Bearer "):
        return False
    try:
        c = jwt.decode(header[7:], KEY.public_key(), algorithms=["RS256"], options={"verify_aud": False})
        return c.get("iss") == f"{BASE}/realms/master"
    except Exception:
        return False


# ------------------------------------------------------------------------------------------------ admin helpers

PERSON_NAME_PROHIBITED = set('<>&"$%!#?§;*~/\\|^=[]{}()')


def brief_user(u):
    return {k: v for k, v in u.items() if not k.startswith("_")}


def role_rep(name):
    r = roles[name]
    return {k: v for k, v in r.items() if not k.startswith("_")}


def client_rep(c, with_secret=True):
    rep = {k: copy.deepcopy(v) for k, v in c.items() if not k.startswith("_")}
    if not with_secret:
        rep.pop("secret", None)
    return rep


def sa_user(c):
    return next((u for u in users.values() if u.get("serviceAccountClientId") == c["clientId"]), None)


def scopes_in_use(sid):
    return any(sid in c["_default"] or sid in c["_optional"] for c in clients.values())


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send(self, status, body=None, headers=None):
        data = b"" if body is None else (json.dumps(body).encode() if not isinstance(body, bytes) else body)
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)
        with open(LOG, "a") as f:
            f.write(f"{self.command} {urlparse(self.path).path} -> {status}\n")

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def json_body(self):
        b = self.body()
        return json.loads(b) if b else None

    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_DELETE(self): self.route("DELETE")

    def route(self, method):
        # SIM_DELAY: artificial latency per request (seconds), to reproduce slow localhost round trips on Windows.
        delay = float(__import__("os").environ.get("SIM_DELAY", "0"))
        if delay:
            time.sleep(delay)
        with LOCK:
            try:
                self._route(method)
            except Exception as e:  # pragma: no cover
                self.send(500, {"error": "sim_error", "detail": repr(e)})

    def _route(self, method):
        u = urlparse(self.path)
        path, q = unquote(u.path), {k: v[0] for k, v in parse_qs(u.query).items()}
        m = re.match(r"^/realms/([^/]+)/(.*)$", path)
        if m and not path.startswith("/admin"):
            return self.realm_endpoint(method, m.group(1), m.group(2))
        m = re.match(r"^/admin/realms/master(/.*)?$", path)
        if m:
            if not verify_admin(self.headers.get("Authorization")):
                return self.send(401, {"error": "HTTP 401 Unauthorized"})
            return self.master_admin(method, m.group(1) or "", q)
        m = re.match(rf"^/admin/realms/{REALM}(/.*)?$", path)
        if m:
            if not verify_admin(self.headers.get("Authorization")):
                return self.send(401, {"error": "HTTP 401 Unauthorized"})
            return self.admin(method, m.group(1) or "", q)
        return self.send(404, {"error": "not_found", "path": path})

    # -------------------------------------------------------------------------------------------- realm

    def realm_endpoint(self, method, realm, rest):
        issuer = f"{BASE}/realms/{realm}"
        if method == "GET" and rest == ".well-known/openid-configuration" and realm == REALM:
            return self.send(200, {"issuer": issuer, "token_endpoint": f"{issuer}/protocol/openid-connect/token",
                                   "jwks_uri": f"{issuer}/protocol/openid-connect/certs",
                                   "id_token_signing_alg_values_supported": ["RS256"],
                                   "authorization_endpoint": f"{issuer}/protocol/openid-connect/auth",
                                   "subject_types_supported": ["public"], "response_types_supported": ["code"]})
        if method == "GET" and rest == "protocol/openid-connect/certs":
            return self.send(200, JWKS)
        if method == "POST" and rest == "protocol/openid-connect/token":
            form = {k: v[0] for k, v in parse_qs(self.body().decode()).items()}
            auth = self.headers.get("Authorization") or ""
            if auth.startswith("Basic "):  # client_secret_basic, as real Keycloak accepts
                cid_, _, sec_ = base64.b64decode(auth[6:]).decode().partition(":")
                from urllib.parse import unquote_plus
                form.setdefault("client_id", unquote_plus(cid_)); form.setdefault("client_secret", unquote_plus(sec_))
            if realm == "master":
                if form.get("client_id") == "admin-cli" and form.get("username") == "admin" \
                        and form.get("password") == ADMIN_PASSWORD and form.get("grant_type") == "password":
                    now = int(time.time())
                    tok = jwt.encode({"iss": issuer, "sub": "admin", "exp": now + 60, "iat": now, "azp": "admin-cli"},
                                     KEY, algorithm="RS256", headers={"kid": KID})
                    return self.send(200, {"access_token": tok, "token_type": "Bearer", "expires_in": 60})
                return self.send(401, {"error": "invalid_grant"})
            if realm != REALM:
                return self.send(404, {"error": "Realm does not exist"})
            client = next((c for c in clients.values() if c["clientId"] == form.get("client_id")), None)
            if not client or not client["enabled"]:
                return self.send(401, {"error": "invalid_client"})
            if not client["publicClient"] and client.get("secret") != form.get("client_secret"):
                return self.send(401, {"error": "unauthorized_client", "error_description": "Invalid client or Invalid client credentials"})
            grant = form.get("grant_type")
            if grant == "client_credentials":
                if client["publicClient"] or not client["serviceAccountsEnabled"]:
                    return self.send(401, {"error": "unauthorized_client"})
                user = sa_user(client)
            elif grant == "password":
                if not client["directAccessGrantsEnabled"]:
                    return self.send(400, {"error": "unauthorized_client"})
                user = next((x for x in users.values() if x["username"] == (form.get("username") or "").lower()
                             and not x.get("serviceAccountClientId")), None)
                if not user or not user["enabled"] or user["_password"] is None or user["_password"] != form.get("password"):
                    return self.send(401, {"error": "invalid_grant"})
            else:
                return self.send(400, {"error": "unsupported_grant_type"})
            return self.send(200, {"access_token": issue(client, user, issuer), "token_type": "Bearer", "expires_in": 900})
        return self.send(404, {"error": "not_found"})

    # -------------------------------------------------------------------------------------------- admin

    def master_admin(self, method, p, q):
        global ADMIN_PASSWORD
        b = self.json_body() if method in ("POST", "PUT") else None
        admin = {"id": MASTER_ADMIN_ID, "username": "admin", "enabled": True}
        if p == "/users" and method == "GET":
            if "username" in q and q["username"] != "admin":
                return self.send(200, [])
            return self.send(200, [admin] if int(q.get("first", 0)) == 0 else [])
        if p == f"/users/{MASTER_ADMIN_ID}/reset-password" and method == "PUT":
            if FAULTS.get("admin_reset"):
                return self.send(500, {"error": "simulated"})
            ADMIN_PASSWORD = b["value"]
            return self.send(204)
        return self.send(404, {"error": "sim_unknown_endpoint", "realm": "master", "path": p})

    def admin(self, method, p, q):
        b = self.json_body() if method in ("POST", "PUT", "DELETE") else None
        # roles
        if p == "/roles" and method == "GET":
            return self.send(200, [role_rep(n) for n in roles])
        if p == "/roles" and method == "POST":
            if b["name"] in roles:
                return self.send(409, {"errorMessage": "Role with name already exists"})
            add_role(b["name"], b.get("description", ""), attributes=b.get("attributes"))
            return self.send(201, None, {"Location": f"{BASE}/admin/realms/{REALM}/roles/{b['name']}"})
        m = re.match(r"^/roles/([^/]+)(/users)?$", p)
        if m:
            name = m.group(1)
            if name not in roles:
                return self.send(404, {"error": "Could not find role"})
            if m.group(2) and method == "GET":
                return self.send(200, [brief_user(x) for x in users.values() if name in x["_roles"]])
            if method == "GET":
                return self.send(200, role_rep(name))
            if method == "DELETE":
                del roles[name]
                for x in users.values():
                    x["_roles"].discard(name)
                return self.send(204)
        # client scopes
        if p == "/client-scopes" and method == "GET":
            return self.send(200, [copy.deepcopy(s) for s in scopes.values()])
        if p == "/client-scopes" and method == "POST":
            if scope_id(b["name"]):
                return self.send(409, {"errorMessage": "Client Scope with name already exists"})
            sid = add_scope(b["name"], [mapper(m_["name"], m_["protocolMapper"], m_.get("config", {})) for m_ in b.get("protocolMappers", [])],
                            b.get("attributes"))
            return self.send(201, None, {"Location": f"{BASE}/admin/realms/{REALM}/client-scopes/{sid}"})
        m = re.match(r"^/client-scopes/([^/]+)/protocol-mappers/models(?:/([^/]+))?$", p)
        if m:
            sid, mid = m.group(1), m.group(2)
            if sid not in scopes:
                return self.send(404, {"error": "Could not find client scope"})
            ms = scopes[sid]["protocolMappers"]
            if mid is None and method == "GET":
                return self.send(200, copy.deepcopy(ms))
            if mid is None and method == "POST":
                if any(x["name"] == b["name"] for x in ms):
                    return self.send(409, {"errorMessage": "Protocol mapper exists with same name"})
                nm = mapper(b["name"], b["protocolMapper"], dict(b.get("config", {})))
                ms.append(nm)
                return self.send(201, None, {"Location": f"{BASE}/admin/realms/{REALM}/client-scopes/{sid}/protocol-mappers/models/{nm['id']}"})
            cur = next((x for x in ms if x["id"] == mid), None)
            if cur is None:
                return self.send(404, {"error": "Model not found"})
            if method == "GET":
                return self.send(200, copy.deepcopy(cur))
            if method == "PUT":
                if b.get("id") != mid:
                    return self.send(400, {"errorMessage": "id mismatch"})
                cur["name"], cur["protocolMapper"], cur["config"] = b["name"], b["protocolMapper"], dict(b.get("config", {}))
                return self.send(204)
            if method == "DELETE":
                ms.remove(cur)
                return self.send(204)
        m = re.match(r"^/client-scopes/([^/]+)$", p)
        if m and method == "DELETE":
            sid = m.group(1)
            if sid not in scopes:
                return self.send(404, {"error": "Could not find client scope"})
            if scopes_in_use(sid):
                return self.send(400, {"errorMessage": "Cannot remove client scope, it is currently in use"})
            del scopes[sid]
            return self.send(204)
        # clients
        if p == "/clients" and method == "GET":
            found = [c for c in clients.values() if "clientId" not in q or c["clientId"] == q["clientId"]]
            return self.send(200, [client_rep(c) for c in found])
        if p == "/clients" and method == "POST":
            if any(c["clientId"] == b["clientId"] for c in clients.values()):
                return self.send(409, {"errorMessage": "Client already exists"})
            c = add_client(b)
            return self.send(201, None, {"Location": f"{BASE}/admin/realms/{REALM}/clients/{c['id']}"})
        m = re.match(r"^/clients/([^/]+)(/.*)?$", p)
        if m:
            c = clients.get(m.group(1))
            if not c:
                return self.send(404, {"error": "Could not find client"})
            sub = m.group(2) or ""
            if sub == "" and method == "GET":
                return self.send(200, client_rep(c))
            if sub == "" and method == "PUT":
                if FAULTS.get("client_put"):
                    return self.send(500, {"error": "simulated"})
                for k in ["name", "enabled", "secret", "serviceAccountsEnabled", "standardFlowEnabled",
                          "directAccessGrantsEnabled", "implicitFlowEnabled", "publicClient", "attributes"]:
                    if k in b:
                        c[k] = b[k]
                return self.send(204)
            if sub == "" and method == "DELETE":
                sa = sa_user(c)
                if sa:
                    del users[sa["id"]]
                del clients[c["id"]]
                return self.send(204)
            if sub == "/service-account-user" and method == "GET":
                sa = sa_user(c)
                return self.send(200, brief_user(sa)) if sa else self.send(400, {"error": "Service account not enabled"})
            m2 = re.match(r"^/(default|optional)-client-scopes(?:/([^/]+))?$", sub)
            if m2:
                key = "_" + m2.group(1)
                if method == "GET" and not m2.group(2):
                    return self.send(200, [{"id": s, "name": scopes[s]["name"]} for s in c[key] if s in scopes])
                sid = m2.group(2)
                if sid not in scopes:
                    return self.send(404, {"error": "Client scope not found"})
                if method == "PUT":
                    other = "_optional" if key == "_default" else "_default"
                    if sid in c[other]:
                        return self.send(409, {"errorMessage": "conflict"})
                    if sid not in c[key]:
                        c[key].append(sid)
                    return self.send(204)
                if method == "DELETE":
                    if sid in c[key]:
                        c[key].remove(sid)
                    return self.send(204)
        # users
        if p == "/partial-export" and method == "POST":
            return self.send(200, {"realm": REALM,
                "roles": {"realm": [role_rep(n) for n in roles]},
                "clients": [dict(client_rep(c), secret="**********") if c.get("secret") else client_rep(c) for c in clients.values()],
                "clientScopes": [copy.deepcopy(x) for x in scopes.values()],
                "groups": [copy.deepcopy(g) for g in groups.values()],
                "passwordPolicy": "length(8)", "smtpServer": {"password": "**********"}})
        if p == "/users" and method == "GET":
            found = list(users.values())
            if "username" in q:
                if q.get("exact") == "true":
                    found = [x for x in found if x["username"] == q["username"].lower()]
                else:
                    found = [x for x in found if q["username"].lower() in x["username"]]
            first, mx = int(q.get("first", 0)), int(q.get("max", 100))
            return self.send(200, [brief_user(x) for x in found][first:first + mx])
        if p == "/users" and method == "POST":
            if any(x["username"] == b["username"].lower() for x in users.values()):
                return self.send(409, {"errorMessage": "User exists with same username"})
            if b.get("email") and not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", b["email"]):
                return self.send(400, {"errorMessage": "invalidEmailMessage", "field": "email"})
            for f in ("firstName", "lastName"):
                if not b.get(f):
                    return self.send(400, {"errorMessage": "error-user-attribute-required", "field": f})
                if any(ch in PERSON_NAME_PROHIBITED for ch in b[f]):
                    return self.send(400, {"errorMessage": "error-person-name-invalid-character", "field": f})
            x = add_user(b)
            return self.send(201, None, {"Location": f"{BASE}/admin/realms/{REALM}/users/{x['id']}"})
        m = re.match(r"^/users/([^/]+)(/.*)?$", p)
        if m:
            x = users.get(m.group(1))
            if not x:
                return self.send(404, {"error": "User not found"})
            sub = m.group(2) or ""
            if sub == "" and method == "GET":
                return self.send(200, brief_user(x))
            if sub == "" and method == "DELETE":
                del users[x["id"]]
                return self.send(204)
            if sub == "/reset-password" and method == "PUT":
                x["_password"] = b["value"]
                return self.send(204)
            if sub == "/role-mappings/realm":
                if method == "GET":
                    return self.send(200, [role_rep(n) for n in sorted(x["_roles"]) if n in roles])
                names = [r["name"] for r in (b or [])]
                if any(n not in roles for n in names):
                    return self.send(404, {"error": "Role not found"})
                if method == "POST":
                    x["_roles"].update(names)
                else:
                    x["_roles"].difference_update(names)
                return self.send(204)
            if sub == "/groups" and method == "GET":
                return self.send(200, [{"id": g, "name": groups[g]["name"], "path": groups[g]["path"]} for g in x["_groups"]])
            m2 = re.match(r"^/groups/([^/]+)$", sub)
            if m2 and method == "PUT":
                if m2.group(1) not in groups:
                    return self.send(404, {"error": "Group not found"})
                x["_groups"].add(m2.group(1))
                return self.send(204)
        # groups
        if p == "/groups" and method == "GET":
            found = [g for g in groups.values()
                     if "search" not in q or (g["name"] == q["search"] if q.get("exact") == "true" else q["search"] in g["name"])]
            return self.send(200, [{"id": g["id"], "name": g["name"], "path": g["path"], "subGroupCount": 0} for g in found])
        m = re.match(r"^/groups/([^/]+)$", p)
        if m and method == "GET":
            g = groups.get(m.group(1))
            return self.send(200, copy.deepcopy(g)) if g else self.send(404, {"error": "Could not find group"})
        return self.send(404, {"error": "sim_unknown_endpoint", "method": method, "path": p})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()

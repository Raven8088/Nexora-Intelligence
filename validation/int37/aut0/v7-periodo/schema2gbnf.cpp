#include "json-schema-to-grammar.h"
#include "json.h"
#include <fstream>
#include <iostream>
#include <sstream>
int main(int argc, char ** argv) {
    std::ifstream in(argv[1]);
    std::stringstream ss; ss << in.rdbuf();
    std::cout << json_schema_to_grammar(common_json::parse(ss.str()), true);
    return 0;
}

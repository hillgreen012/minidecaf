lexer grammar MDLexer;

WhiteSpaces: [ \t\r\n]+ -> skip;

IF:         'if';
ELSE:       'else';
WHILE:      'while';
RETURN:     'return';

INT:        'int';

Integer:    [0-9]+;
Identifier: [a-zA-Z_][a-zA-Z0-9_]*;

ASSIGN:     '=';
PLUS:       '+';
MINUS:      '-';
STAR:       '*';
SLASH:      '/';
NOT:        '!';
TILDE:      '~';
AND:        '&';
HAT:        '^';
OR:         '|';
SL:         '<<';
SR:         '>>';
EQ:         '==';
NE:         '!=';
LT:         '<';
GT:         '>';
LE:         '<=';
GE:         '>=';
LAND:       '&&';
LOR:        '||';
SEMICOLON:  ';';
LPAREN:     '(';
RPAREN:     ')';
LBRACK:     '{';
RBRACK:     '}';
COMMA:      ',';


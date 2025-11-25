program        -> declaration* EOF ;

declaration    -> letDecl
                | statement ;

letDecl        -> VARIABLE_BIND | FUNCTION_BIND ;
VARIABLE_BIND  -> "let" IDENTIFIER (":" TYPE )? ( "=" expression )? ";" ;
FUNCTION_BIND  -> "let" IDENTIFIER "(" ARGUMENTS ")" "->" BODY;
BODY           -> SINGLE_EXPRESSION | FULL_BODY;
statement      -> exprStmt
                | printStmt 
                | block ;

exprStmt       -> expression ";" ;
printStmt      -> "print" "(" expression ")" ";" ;
block          -> "{" declaration* "}"

expression     -> assignment ;
assignment     -> IDENTIFIER "=" assignment
                | equality ;
equality       -> comparison ( ( "!=" | "==" ) comparison )* ;
comparison     -> term ( ( ">" | ">=" | "<" | "<=" ) )
term           -> factor ( ( "-" | "+" ) factor )* ;
factor         -> unary ( ( "/" | "*" ) unary )* ;
unary          -> ( "not" | "-" | "+" ) unary 
                | primary ;
primary        -> NUMBER | STRING | lambda 
                | "True" | "False" | "Null" | "Unit" 
                | IDENTIFIER ;

lambda         -> "(" ARGUMENTS ")" "->" body ;
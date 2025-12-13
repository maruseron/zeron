package com.maruseron.zeron.scan;

public enum TokenType {
    // Single-character tokens
    LEFT_PAREN, RIGHT_PAREN,        // ( )
    LEFT_BRACE, RIGHT_BRACE,        // { }
    LEFT_BRACKET, RIGHT_BRACKET,    // [ ]
    COMMA, SEMICOLON,               // , ;
    PIPE, AMPERSAND,                // | &

    // One, two or three character tokens
    DOT, DOT_DOT,                   // . ..
    COLON, COLON_COLON,             // : ::
    MINUS, MINUS_EQUAL, ARROW,      // - -= ->
    PLUS, PLUS_EQUAL,               // + +=
    SLASH, SLASH_EQUAL, SLASH_STAR, // / /= /*
    STAR, STAR_EQUAL, STAR_SLASH,   // * *= */
    BANG, BANG_EQUAL,               // ! !=
    HUH, HUH_DOT,                   // ? ?.
    EQUAL, EQUAL_EQUAL,             // = ==
    GREATER, GREATER_EQUAL,         // > >=
    LESS, LESS_EQUAL,               // < <=

    // Literals
    IDENTIFIER, STRING, INT, DOUBLE,

    AND, BREAK, CLASS, CONTRACT, CONSTRUCTOR, ELSE, FALSE, FN,
    FOR, GET, IF, IMPLEMENT, IN, IS, LET, LOOP, MATCH, MUT,
    NOT, NULL, OR, PUBLIC, PRINT, PRIVATE, RETURN, SET, THEN,
    THIS, TRUE, TYPE, TYPEOF, UNIT, UNTIL, WHILE,

    NEWLINE, EOF
}

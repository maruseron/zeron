package com.maruseron.zeron.scan;

public enum TokenType {
    // Single-character tokens
    LEFT_PAREN, RIGHT_PAREN,        // ( )
    LEFT_BRACE, RIGHT_BRACE,        // { }
    LEFT_BRACKET, RIGHT_BRACKET,    // [ ]
    COMMA, SEMICOLON,               // , ;
    TILDE,                          // ~

    // One, two or three character tokens
    DOT, DOT_DOT,                   // . ..
    COLON, COLON_COLON,             // : ::
    MINUS, MINUS_EQUAL, ARROW,      // - -= ->
    PLUS, PLUS_EQUAL,               // + +=
    PERCENT, PERCENT_EQUAL,         // % %=
    SLASH, SLASH_EQUAL, SLASH_STAR, // / /= /*
    STAR, STAR_EQUAL, STAR_SLASH,   // * *= */
    PIPE, PIPE_EQUAL,               // | |=
    AMPERSAND, AMPERSAND_EQUAL,     // & &=
    CARET, CARET_EQUAL,             // ^ ^=
    SHIFT_LEFT, SHIFT_LEFT_EQUAL,   // << <<=
    SHIFT_RIGHT, SHIFT_RIGHT_EQUAL, // >> >>=
    UNSIGNED_SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT_EQUAL, // >>> >>>=
    BANG, BANG_EQUAL,               // ! !=
    HUH, HUH_DOT, HUH_HUH, HUH_HUH_EQUAL, // ? ?. ?? ??=
    EQUAL, EQUAL_EQUAL, EQUAL_EQUAL_EQUAL, // = == ===
    GREATER, GREATER_EQUAL,         // > >=
    LESS, LESS_EQUAL,               // < <=

    // Literals
    IDENTIFIER, STRING, INT, DOUBLE,

    AND, AS, BREAK, CLASS, CONTRACT, CONSTRUCTOR, DEFAULT, ELSE, EXTERNAL, FALSE, FN,
        CONTINUE,
    FOR, GET, IF, IMPLEMENT, IMPORT, IN, IS, LET, LOOP, MATCH, MUT,
    NOT, NULL, OR, PACKAGE, PERMITS, PROPERTY, PUBLIC, PRIVATE, RETURN, SEALED, SET, THEN,
    THIS, TRUE, TYPE, TYPEOF, UNIT, UNTIL, WHILE,

    NEWLINE, EOF
}

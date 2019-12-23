package com.jackvanlightly.rabbittesttool;

public class CmdArgumentException extends RuntimeException {
    public CmdArgumentException() {
    }

    public CmdArgumentException(String s) {
        super(s);
    }

    public CmdArgumentException(String s, Throwable throwable) {
        super(s, throwable);
    }
}

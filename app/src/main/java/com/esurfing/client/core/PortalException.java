package com.esurfing.client.core;

/**
 * 认证过程中的可预期错误；{@link #code} / {@link #sub} 是服务端回的错误码。
 */
public class PortalException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String sub;

    public PortalException(String message) {
        this(message, null, null);
    }

    public PortalException(String message, String code, String sub) {
        super(message);
        this.code = code;
        this.sub = sub;
    }

    public PortalException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
        this.sub = null;
    }

    public String code() {
        return code;
    }

    public String sub() {
        return sub;
    }

    /** 给用户看的完整原因。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(getMessage() == null ? "未知错误" : getMessage());
        if (code != null && !code.isEmpty()) {
            sb.append("（Error-Code=").append(code);
            if (sub != null && !sub.isEmpty()) {
                sb.append(", Sub-Error=").append(sub);
            }
            sb.append('）');
        }
        return sb.toString();
    }
}

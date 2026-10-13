package editor.integration;
public final class ServiceFailure extends RuntimeException {
    private final String code,requestId;
    private final int httpStatus;
    public ServiceFailure(String code,String message,String requestId,int httpStatus) {
        super(message);this.code=code;this.requestId=requestId;this.httpStatus=httpStatus;
    }
    public ServiceFailure(String code,String message,Throwable cause) {
        super(message,cause);this.code=code;this.requestId=null;this.httpStatus=0;
    }
    public String code() {return code;}
    public String requestId() {return requestId;}
    public int httpStatus() {return httpStatus;}
}

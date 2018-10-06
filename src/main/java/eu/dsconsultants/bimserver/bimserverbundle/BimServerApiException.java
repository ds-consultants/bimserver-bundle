package eu.dsconsultants.bimserver.bimserverbundle;

public class BimServerApiException extends Exception {

    public BimServerApiException() {
    }

    public BimServerApiException(String message) {
        super(message);
    }

    public BimServerApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public BimServerApiException(Throwable cause) {
        super(cause);
    }

    public BimServerApiException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}

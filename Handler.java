import java.util.concurrent.ExecutionException;

public interface Handler {
    ApplicationStatusResponse performOperation(String id) throws ExecutionException, InterruptedException;

}

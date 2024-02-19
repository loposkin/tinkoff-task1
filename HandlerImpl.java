import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class HandlerImpl implements Handler {
    private final static long TIMEOUT = 15L;
    private final Client client;
    private final AtomicInteger retriesCount;

    public HandlerImpl(Client client) {
        this.client = client;
        this.retriesCount = new AtomicInteger();
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) throws ExecutionException, InterruptedException {
        CompletableFuture<Response> future1 = makeCall(() -> client.getApplicationStatus1(id), retriesCount);
        CompletableFuture<Response> future2 = makeCall(() -> client.getApplicationStatus2(id), retriesCount);
        return anyOfSuccessfulOrAllOfFailure(future1, future2)
                .thenApply(this::processResponse)
                .completeOnTimeout(new ApplicationStatusResponse.Failure(null, retriesCount.get()), TIMEOUT, TimeUnit.SECONDS)
                .get();
    }

    private CompletableFuture<Response> anyOfSuccessfulOrAllOfFailure(CompletableFuture<?>... cfs) {
            return CompletableFuture.anyOf(
                    Arrays.stream(cfs)
                        .map(cf -> {
                            AtomicInteger failureNumber = new AtomicInteger();
                            CompletableFuture<Response> cfWithCheck = new CompletableFuture<>();
                            cf.thenAccept(o -> {
                                if (o instanceof Response.Success success) {
                                    cfWithCheck.complete(success);
                                } else if (o instanceof Response.Failure failure) {
                                    int currentFailureNumber = failureNumber.incrementAndGet();
                                    if (currentFailureNumber == cfs.length) {
                                        cfWithCheck.complete(failure);
                                    }
                                }
                            });

                            return cfWithCheck;
                        })
                        .toArray(CompletableFuture[]::new)
            ).thenApply(o -> (Response) o);
    }

    private ApplicationStatusResponse processResponse(Object response) {
        if (response instanceof Response.Success success) {
            return new ApplicationStatusResponse.Success(success.applicationId(), success.applicationStatus());
        } else {
            return new ApplicationStatusResponse.Failure(null, retriesCount.get());
        }
    }

    private Response retryableCall(Supplier<Response> supplier, AtomicInteger retriesCount) {
        while (true) {
            Response response = supplier.get();
            if (response instanceof Response.RetryAfter) {
                try {
                    wait(((Response.RetryAfter) response).delay().toMillis());
                    retriesCount.incrementAndGet();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                return response;
            }
        }
    }

    private CompletableFuture<Response> makeCall(Supplier<Response> supplier, AtomicInteger retriesCount) {
        return CompletableFuture.supplyAsync(() -> retryableCall(supplier, retriesCount));
    }
}

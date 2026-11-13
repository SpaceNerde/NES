package de.nes.core.provider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface InstanceProvider extends AutoCloseable {
    CompletableFuture<ProviderInstance>         create(CreateRequest request);
    CompletableFuture<ProviderInstance>         inspect(String ref);
    CompletableFuture<List<ProviderInstance>>   listManaged();
    CompletableFuture<Void>                     stop(String ref, Duration grace);
    CompletableFuture<Void>                     remove(String ref);
    CompletableFuture<List<String>>             logs(String ref, int lines);
}
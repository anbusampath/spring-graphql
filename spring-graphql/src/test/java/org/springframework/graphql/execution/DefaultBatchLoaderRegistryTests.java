/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.graphql.execution;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import graphql.ExecutionInput;
import graphql.GraphQLContext;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.stats.NoOpStatisticsCollector;
import org.dataloader.stats.StatisticsCollector;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import org.springframework.graphql.Book;
import org.springframework.graphql.BookSource;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

/**
 * Unit tests for {@link DefaultBatchLoaderRegistry}.
 * @author Rossen Stoyanchev
 */
public class DefaultBatchLoaderRegistryTests {

	private final BatchLoaderRegistry batchLoaderRegistry = new DefaultBatchLoaderRegistry();

	private final DataLoaderRegistry dataLoaderRegistry = DataLoaderRegistry.newRegistry().build();


	@Test
	void batchLoader() throws Exception {
		AtomicReference<String> valueRef = new AtomicReference<>();

		this.batchLoaderRegistry.forTypePair(Long.class, Book.class)
				.withOptions(options -> options.setBatchingEnabled(false)) // DataLoader invoked immediately
				.registerBatchLoader((ids, environment) ->
						Flux.deferContextual(contextView -> {
							valueRef.set(contextView.get("key"));
							return Flux.fromIterable(ids).map(BookSource::getBook);
						}));

		GraphQLContext graphQLContext = initGraphQLContext(Context.of("key", "value"));
		this.batchLoaderRegistry.registerDataLoaders(this.dataLoaderRegistry, graphQLContext);

		Map<String, DataLoader<?, ?>> map = this.dataLoaderRegistry.getDataLoadersMap();
		assertThat(map).hasSize(1).containsKey(Book.class.getName());

		// Invoke DataLoader to check the context
		((DataLoader<Long, Book>) map.get(Book.class.getName())).load(1L).get();
		assertThat(valueRef.get()).isEqualTo("value");
	}

	@Test
	void mappedBatchLoader() throws Exception {
		AtomicReference<String> valueRef = new AtomicReference<>();

		this.batchLoaderRegistry.forTypePair(Long.class, Book.class)
				.withOptions(options -> options.setBatchingEnabled(false)) // DataLoader invoked immediately
				.registerMappedBatchLoader((ids, environment) ->
						Mono.deferContextual(contextView -> {
							valueRef.set(contextView.get("key"));
							return Flux.fromIterable(ids).map(BookSource::getBook).collectMap(Book::getId, Function.identity());
						}));

		GraphQLContext graphQLContext = initGraphQLContext(Context.of("key", "value"));
		this.batchLoaderRegistry.registerDataLoaders(this.dataLoaderRegistry, graphQLContext);

		Map<String, DataLoader<?, ?>> map = this.dataLoaderRegistry.getDataLoadersMap();
		assertThat(map).hasSize(1).containsKey(Book.class.getName());

		// Invoke DataLoader to check the context
		((DataLoader<Long, Book>) map.get(Book.class.getName())).load(1L).get();
		assertThat(valueRef.get()).isEqualTo("value");
	}

	@Test
	void batchLoaderWithCustomNameAndOptions() {
		String name = "myLoader";
		StatisticsCollector collector = new NoOpStatisticsCollector();

		this.batchLoaderRegistry.forName(name)
				.withOptions(options -> options.setStatisticsCollector(() -> collector))
				.registerBatchLoader((keys, environment) -> Flux.empty());

		this.batchLoaderRegistry.registerDataLoaders(this.dataLoaderRegistry, GraphQLContext.newContext().build());

		Map<String, DataLoader<?, ?>> map = dataLoaderRegistry.getDataLoadersMap();
		assertThat(map).hasSize(1).containsKey(name);
		assertThat(map.get(name).getStatistics()).isSameAs(collector.getStatistics());
	}

	private GraphQLContext initGraphQLContext(ContextView context) {
		ExecutionInput executionInput = ExecutionInput.newExecutionInput().query("").build();
		ReactorContextManager.setReactorContext(context, executionInput);
		return executionInput.getGraphQLContext();
	}

}

/*
 * Copyright 2023-present the original author or authors.
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

package org.springaicommunity.session.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * One plain Redis container and connection factory, shared by the tests of this module.
 * Plain Redis rather than Redis Stack, to show the repository needs no modules.
 *
 * @author Soby Chacko
 */
final class RedisSessionTestSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379)
		.withCommand("redis-server", "--maxmemory-policy", "noeviction");

	private static LettuceConnectionFactory connectionFactory;

	private RedisSessionTestSupport() {
	}

	static synchronized LettuceConnectionFactory connectionFactory() {
		if (connectionFactory == null) {
			REDIS.start();
			LettuceConnectionFactory factory = new LettuceConnectionFactory(
					new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
			factory.afterPropertiesSet();
			factory.start();
			connectionFactory = factory;
		}
		return connectionFactory;
	}

}

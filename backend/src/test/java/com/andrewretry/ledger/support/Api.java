package com.andrewretry.ledger.support;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the running app over HTTP. Never throws on 4xx/5xx and never follows redirects, so tests can assert on them. */
public final class Api {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	public record Response(int status, HttpHeaders headers, String body) {

		public JsonNode json() {
			return JSON.readTree(body);
		}

		public String header(String name) {
			return headers.getFirst(name);
		}

		/** A property of the JSON body as text, e.g. {@code code()} for problem responses. */
		public String text(String field) {
			return json().path(field).asString();
		}
	}

	private final RestClient client;

	public Api(int port) {
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		this.client = RestClient.builder()
				.baseUrl("http://localhost:" + port)
				.requestFactory(new JdkClientHttpRequestFactory(http))
				.build();
	}

	/** {@code headers} are name, value pairs. */
	public Response get(String path, String... headers) {
		return client.get().uri(path).headers(h -> addAll(h, headers)).exchange(Api::read);
	}

	public Response post(String path, String jsonBody, String... headers) {
		return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(jsonBody)
				.headers(h -> addAll(h, headers)).exchange(Api::read);
	}

	private static void addAll(HttpHeaders target, String[] pairs) {
		for (int i = 0; i < pairs.length; i += 2) {
			target.add(pairs[i], pairs[i + 1]);
		}
	}

	private static Response read(org.springframework.http.HttpRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
			throws java.io.IOException {
		String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
		return new Response(response.getStatusCode().value(), response.getHeaders(), body);
	}

}

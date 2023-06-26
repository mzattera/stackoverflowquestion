/*
 * Copyright 2023 Massimiliano "Maxi" Zattera
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.mzattera.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.reactivex.Single;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import retrofit2.HttpException;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Url;

public class Test {

	// Your inference endpoint here
	// The below one is public and can be used without a key
	public static final String INFERENCE_ENDPOINT = "https://by62y2zqbeqalfay.eu-west-1.aws.endpoints.huggingface.cloud";

	// Your API key here
	private static final String API_KEY = "public-endpoint-no-key-needed";

	private final static ObjectMapper JSON_MAPPER;
	static {
		JSON_MAPPER = new ObjectMapper();
		JSON_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		JSON_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		JSON_MAPPER.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
	}

	// API request object (will be serialized as JSON)
	public static class TextGenerationRequest {
		public List<String> inputs = new ArrayList<>();
	}

	// API response object (will be de-serialized from JSON)
	public static class TextGenerationResponse {
		public String generatedText;
	}

	// API to call HF
	public static interface HuggingFaceApi {
		@POST
		Single<List<List<TextGenerationResponse>>> textGeneration(@Url String url, @Body TextGenerationRequest req);
	}

	// Client to call HF
	public final static class HuggingFaceClient {

		private final static String API_BASE_URL = "https://api-inference.huggingface.co/models/";

		private final HuggingFaceApi api;

		private final OkHttpClient client;

		public HuggingFaceClient() {

			client = new OkHttpClient.Builder().connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
					.readTimeout(6, TimeUnit.MINUTES).addInterceptor(new Interceptor() {
						@Override
						public Response intercept(Chain chain) throws IOException {
							return chain.proceed(
									chain.request().newBuilder().header("Authorization", "Bearer " + Test.API_KEY)
											.header("Content-Type", "application/json").build());
						}
					}).addInterceptor(new Interceptor() {

						@Override
						public Response intercept(Chain chain) throws IOException {
							Request req = chain.request();
							System.out.println(req.method() + " " + req.url() + "  ???");
							for (String h : req.headers().names()) {
								System.out.println(h + ": " + req.headers().get(h));
							}
							System.out.print("[Content-Type: " + req.body().contentType().toString()
									+ ",Content-Length: " + req.body().contentLength() + "] ");
							System.out.println(req.body().isDuplex() + " " + req.body().isOneShot());

							final Request copy = req.newBuilder().build();
							final Buffer buffer = new Buffer();
							copy.body().writeTo(buffer);
							System.out.println(buffer.readUtf8());

							return chain.proceed(req);
						}
					}).build();

			Retrofit retrofit = new Retrofit.Builder().baseUrl(API_BASE_URL).client(client)
					.addConverterFactory(JacksonConverterFactory.create(Test.JSON_MAPPER))
					.addCallAdapterFactory(RxJava2CallAdapterFactory.create()).build();

			api = retrofit.create(HuggingFaceApi.class);
		}

		// Only method API exposes
		public String textGeneration(String url, TextGenerationRequest req) {
			return callApi(api.textGeneration(url, req)).get(0).get(0).generatedText;
		}

		private <T> T callApi(Single<T> apiCall) {
			try {
				return apiCall.blockingGet();
			} catch (Exception e) {
				e.printStackTrace(System.err);
				if (e.getCause() != null) {
					System.out.println("Error caused by:");
					e.getCause().printStackTrace(System.err);
				}
				throw e;
			}
		}
	}

	public static void main(String[] args) throws Exception {
		testHttpClient();
		testHRetrofit(INFERENCE_ENDPOINT);
	}

	/**
	 * Below test, calling inference endpoint using Apache HttpClient works.
	 */
	private static void testHttpClient() throws ClientProtocolException, IOException {

		System.out.println("---[Apache HtttpClient with URL: " + INFERENCE_ENDPOINT + "]------------");

		try (CloseableHttpClient client = HttpClients.createDefault()) {

			final HttpPost httpPost = new HttpPost(INFERENCE_ENDPOINT + "/");

			httpPost.addHeader("Authorization", "Bearer " + API_KEY);
			httpPost.addHeader("Content-Type", "application/json");

			TextGenerationRequest req = new TextGenerationRequest();
			req.inputs.add("Alan Turing was");
			String json = JSON_MAPPER.writeValueAsString(req);
			httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

			System.out.println(httpPost.getRequestLine().toString());
			for (Header h : httpPost.getAllHeaders()) {
				System.out.println(h.toString());
			}
			System.out.println(httpPost.getEntity().toString());
			System.out.println(new String(httpPost.getEntity().getContent().readAllBytes()));

			HttpResponse resp = client.execute(httpPost);
			StatusLine statusLine = resp.getStatusLine();
			String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

			System.out.println(statusLine);
			System.out.println("---------------");
			Object obj = JSON_MAPPER.readValue(responseBody, Object.class);
			System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
			System.out.println("---------------");
		}
	}

	/**
	 * Below test using Retrofit works by relative URL (model name) but not with
	 * full URL.
	 * 
	 * @param url This is either a model, to be added to base URL or a full URL, to
	 *            be used as-is.
	 */
	private static void testHRetrofit(String url) {
		System.out.println("---[Retrofit 2 with URL: " + url + "]------------");

		HuggingFaceClient cli = new HuggingFaceClient();

		TextGenerationRequest req = new TextGenerationRequest();
		req.inputs.add("Alan Turing was");

		String resp = cli.textGeneration(url, req);
		System.out.println(resp);
		System.out.println("---------------");
	}
}

package com.logadviser.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@Singleton
public class TempleOsrsClient
{
	private static final HttpUrl ENDPOINT = HttpUrl.parse(
		"https://templeosrs.com/api/collection-log/player_collection_log.php");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	public TempleOsrsClient(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	public void fetchObtainedAsync(String playerName, Consumer<Set<Integer>> onResult)
	{
		if (ENDPOINT == null || playerName == null || playerName.isEmpty())
		{
			onResult.accept(null);
			return;
		}
		HttpUrl url = ENDPOINT.newBuilder()
			.addQueryParameter("player", playerName)
			.build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("TempleOSRS warm-start failed for {}: {}", playerName, e.toString());
				onResult.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.debug("TempleOSRS responded {}", response.code());
						onResult.accept(null);
						return;
					}
					Set<Integer> obtained = parseObtained(body.string(), gson);
					onResult.accept(obtained);
				}
				catch (RuntimeException ex)
				{
					log.warn("Failed to parse TempleOSRS payload", ex);
					onResult.accept(null);
				}
			}
		});
	}

	private static Set<Integer> parseObtained(String json, Gson gson)
	{
		Set<Integer> out = new HashSet<>();
		JsonElement root = gson.fromJson(json, JsonElement.class);
		if (root == null || !root.isJsonObject())
		{
			return out;
		}
		JsonObject obj = root.getAsJsonObject();
		// Temple typically returns { "data": { "items": { "<itemId>": { "count": n }, ... } } }
		// or { "data": [<itemId>, ...] }. We handle both.
		JsonElement dataEl = obj.has("data") ? obj.get("data") : obj;
		if (dataEl.isJsonObject())
		{
			JsonObject dataObj = dataEl.getAsJsonObject();
			if (dataObj.has("items") && dataObj.get("items").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> e : dataObj.getAsJsonObject("items").entrySet())
				{
					tryAdd(out, e.getKey());
				}
			}
			else
			{
				for (java.util.Map.Entry<String, JsonElement> e : dataObj.entrySet())
				{
					tryAdd(out, e.getKey());
				}
			}
		}
		else if (dataEl.isJsonArray())
		{
			JsonArray arr = dataEl.getAsJsonArray();
			for (JsonElement e : arr)
			{
				if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())
				{
					out.add(e.getAsInt());
				}
				else if (e.isJsonObject() && e.getAsJsonObject().has("itemId"))
				{
					out.add(e.getAsJsonObject().get("itemId").getAsInt());
				}
			}
		}
		return out;
	}

	private static void tryAdd(Set<Integer> out, String key)
	{
		try
		{
			out.add(Integer.parseInt(key));
		}
		catch (NumberFormatException ignored)
		{
			// not an item id
		}
	}

	@SuppressWarnings("unused")
	private static String encode(String s)
	{
		try
		{
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
		}
		catch (Exception e)
		{
			return s;
		}
	}
}

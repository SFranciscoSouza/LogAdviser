package com.logadviser.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the player's Collections Logged rank + score from the OSRS Hiscores
 * (the public index_lite.json endpoint). Hiscores are tied to the player's actual
 * account type, so the endpoint is chosen from the in-game detected type, not the
 * sidebar override.
 */
@Slf4j
@Singleton
public class HiscoreRankFetcher
{
	private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

	@Value
	public static class Result
	{
		long rank;
		long score;

		public boolean isUnranked()
		{
			return rank < 0;
		}
	}

	public enum Endpoint
	{
		MAIN("hiscore_oldschool"),
		IRONMAN("hiscore_oldschool_ironman"),
		HARDCORE_IRONMAN("hiscore_oldschool_hardcore_ironman"),
		ULTIMATE_IRONMAN("hiscore_oldschool_ultimate");

		private final String segment;

		Endpoint(String segment)
		{
			this.segment = segment;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private long lastFetchAt = 0L;
	private String lastPlayer = "";
	private Result lastResult = null;

	@Inject
	public HiscoreRankFetcher(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	public void fetchAsync(String playerName, Endpoint endpoint, BiConsumer<Result, Throwable> done)
	{
		if (playerName == null || playerName.isEmpty())
		{
			done.accept(null, null);
			return;
		}
		long now = System.currentTimeMillis();
		if (playerName.equalsIgnoreCase(lastPlayer)
			&& lastResult != null
			&& (now - lastFetchAt) < CACHE_TTL_MS)
		{
			done.accept(lastResult, null);
			return;
		}
		HttpUrl url = HttpUrl.parse(
			"https://secure.runescape.com/m=" + endpoint.segment + "/index_lite.json")
			.newBuilder()
			.addQueryParameter("player", playerName)
			.build();
		Request req = new Request.Builder().url(url).get().build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Hiscore fetch failed for {}: {}", playerName, e.toString());
				done.accept(null, e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						done.accept(null, null);
						return;
					}
					Result r = parseCollectionsLogged(body.string(), gson);
					if (r != null)
					{
						lastFetchAt = System.currentTimeMillis();
						lastPlayer = playerName;
						lastResult = r;
					}
					done.accept(r, null);
				}
				catch (RuntimeException ex)
				{
					log.warn("Failed to parse hiscores payload", ex);
					done.accept(null, ex);
				}
			}
		});
	}

	public void invalidate()
	{
		lastFetchAt = 0L;
		lastPlayer = "";
		lastResult = null;
	}

	private static Result parseCollectionsLogged(String json, Gson gson)
	{
		JsonElement root = gson.fromJson(json, JsonElement.class);
		if (root == null || !root.isJsonObject())
		{
			return null;
		}
		JsonObject obj = root.getAsJsonObject();
		if (!obj.has("activities"))
		{
			return null;
		}
		JsonArray acts = obj.getAsJsonArray("activities");
		for (JsonElement el : acts)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject a = el.getAsJsonObject();
			String name = a.has("name") ? a.get("name").getAsString() : "";
			if (name.equalsIgnoreCase("Collections Logged"))
			{
				long rank = a.has("rank") ? a.get("rank").getAsLong() : -1;
				long score = a.has("score") ? a.get("score").getAsLong() : -1;
				return new Result(rank, score);
			}
		}
		return null;
	}
}

package cis5550.jobs;
import java.io.*;

import cis5550.tools.PorterStemmer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import cis5550.kvs.*;
import cis5550.flame.*;
import cis5550.flame.FlameContext.RowToString;
import cis5550.flame.FlameContext.RowToPair;
import cis5550.flame.FlamePairRDD.PairToPairIterable;
import cis5550.flame.FlamePairRDD.PairToStringIterable;
import cis5550.flame.FlamePairRDD.TwoStringsToString;
import cis5550.flame.FlameRDD.StringToPair;
import cis5550.tools.Hasher;
import cis5550.jobs.datamodels.TableColumns;
import cis5550.tools.Logger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class Indexer {
	private static final Logger LOGGER = Logger.getLogger(Indexer.class);
	private static final String CRAWL_TABLE = "pt-crawl";
	private static final String INDEX_TABLE = "pt-index";
	private static final String TO_INDEX = "pt-toindex";
	private static final String URL_REF = TableColumns.URL.value();
	private static final String PAGE_REF = TableColumns.PAGE.value();
	private static final String INDEXED_TABLE = "pt-indexed";
	private static final String SPACE = " ";
	public static void run(FlameContext context, String[] arr) throws Exception {
		RowToPair lambda1 = (Row myRow) -> {
			if (myRow.columns().contains(URL_REF) && myRow.columns().contains(PAGE_REF)) {
				KVSClient kvsClient = context.getKVS();
				try {
					// using hashed url as key
					if (kvsClient.existsRow(TO_INDEX, myRow.key())) {
						return null;
					} else {
						if (myRow.get(URL_REF) != null && myRow.get(PAGE_REF) != null) {
							kvsClient.putRow(TO_INDEX, myRow);
							return null;
							// return new FlamePair(URLDecoder.decode(myRow.get(URL_REF), StandardCharsets.UTF_8), myRow.get(PAGE_REF));
						} else {
							return null;
						}
					}
				} catch (Exception e) {
					LOGGER.error("KVS error: putting/accessing alr-indexed table");
				}
				return null;

			} else {
				return null;
			}
		};

		FlamePairRDD myPairs = context.pairFromTable(CRAWL_TABLE, lambda1);

		RowToString lambda3 = (Row myRow) -> {
			try {
				Thread.sleep(100);
			} catch (Exception e) {
				LOGGER.error("screaming crying throwing up");
			}

			KVSClient kvsClient = context.getKVS();

			String url = URLDecoder.decode(myRow.get(URL_REF), StandardCharsets.UTF_8);
			try {
				if (alreadyTraversed(context, URLDecoder.decode(myRow.get(URL_REF), StandardCharsets.UTF_8))) {
					System.out.println("alr done: " + myRow.get(URL_REF));
					return null;
				}
				Row urlIndexed = new Row(Hasher.hash(url));
				urlIndexed.put(URL_REF, url);
				kvsClient.putRow(INDEXED_TABLE, urlIndexed);
			} catch (Exception e) {
				LOGGER.error("Error checking traversal");
			}

			String removedTags = "";

			removedTags = myRow.get(PAGE_REF).replaceAll("<[^>]*>", " ");
			removedTags = removedTags.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");

			String[] wordsList = removedTags.split(SPACE);

			HashSet<String> words = new HashSet<>();
			HashMap<String, String> wordPositions = new HashMap<>();
			int index = 0;

			for (String word : wordsList) {
				if (word == null || word.equals(SPACE) || word.equals("")) {
					continue;
				}
				index++;
				words.add(word);
				if (wordPositions.containsKey(word)) {
					wordPositions.put(word, wordPositions.get(word) + SPACE + index);
				} else {
					wordPositions.put(word, index + "");
				}
			}

			// String url = URLDecoder.decode(myRow.get(URL_REF), StandardCharsets.UTF_8);
			System.out.println(url);
			HashMap<String, String> myRowValueMap = new HashMap<>();
			for (String w : words) {
				try {
					String val = url + ":" + wordPositions.get(w);
					w = w.replaceAll("\\s", "");
					if (w != null && !w.equals("") && !w.equals(" ") && !val.equals("")) {
						if (w.length() <= 25 && w.length() > 0) {
							myRowValueMap.put(w, val);
							// kvsClient.appendToRow(INDEX_TABLE, w, URL_REF, val, ",");
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					LOGGER.error("Error:" + w);
				}
			}
			if (myRowValueMap != null && myRowValueMap.size() > 0) {
				// kvsClient.batchAppendToRow(INDEX_TABLE, URL_REF, myRowValueMap);
				try {
					kvsClient.batchAppendToRow(INDEX_TABLE, URL_REF, myRowValueMap);
				} catch (Exception e) {
					LOGGER.error("Error in appending: " + url);
				}
			}

			return null;
		};
		FlameRDD inverted = context.fromTable(TO_INDEX, lambda3);
		myPairs.destroy();
	}

	private static boolean alreadyTraversed(FlameContext aContext, String aUrl) throws Exception {
		return aContext.getKVS().get(INDEXED_TABLE, Hasher.hash(aUrl), URL_REF) != null;
	}
}
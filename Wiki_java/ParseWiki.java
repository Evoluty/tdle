/* 
 *
 * Parse 
 * <lang>wiki-<date>-page.sql.gz
 * <lang>wiki-<date>-page-links.sql.gz
 * to produce simplified files with page ids and links
 * 
 * Extracted from the original code :
 *
 *
 * Copyright (c) 2016 Project Nayuki
 * All rights reserved. Contact Nayuki for licensing.
 * https://www.nayuki.io/page/computing-wikipedias-internal-pageranks
 */

import java.io.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;


/* 
 * This program reads the .sql.gz files containing Wikipedia's page metadata and page links
 * (or reads the cache files), writes out cached versions of the parsed data (for faster processing
 * next time), iteratively computes the PageRank of every page, and writes out the raw PageRank vector.
 * 
 * Run the program on the command line with no arguments. You may need to modify the file names below.
 * The program prints a bunch of statistics and progress messages on standard output.
 */

public final class ParseWiki
{
	private static final int PRINT_INTERVAL = 30;
	private static final int PAGERANK_ITERATIONS = 200; // 200 iterations is enough to converge
	
	/*---- Input/output files configuration ----*/
	
	private static final File PAGE_ID_TITLE_SQL_FILE = new File("frwiki-latest-page.sql.gz");           // Original input file
	private static final File PAGE_ID_TITLE_RAW_FILE = new File("wikipedia-page-id-title.raw");  // Cache after preprocessing
	
	private static final File PAGE_LINKS_SQL_FILE = new File("frwiki-latest-pagelinks.sql.gz");   // Original input file
	private static final File PAGE_LINKS_RAW_FILE = new File("wikipedia-page-links.raw");  // Cache after preprocessing

	private static final File PAGERANK_FILE = new File("pagerank.txt"); // File to store the computed pagerank
	private static final File SORTED_PAGERANK_FILE = new File("sorted-pagerank.txt"); // File to store the sorted pagerank
	
	/*---- Main program ----*/
	
	public static void main(String[] args) throws IOException
	{
		// Read page-ID-title data
		Map<String,Integer> titleToId;
		if (!PAGE_ID_TITLE_RAW_FILE.isFile()) // Read SQL and write cache
		{
			titleToId = PageIdTitleMap.readSqlFile(PAGE_ID_TITLE_SQL_FILE);
			PageIdTitleMap.writeRawFile(titleToId, PAGE_ID_TITLE_RAW_FILE);
		}
		else  // Read cache
		{
			titleToId = PageIdTitleMap.readRawFile(PAGE_ID_TITLE_RAW_FILE);
		}
		Map<Integer,String> idToTitle = PageIdTitleMap.computeReverseMap(titleToId);
		
		// Read page-links data
		int[] links;
		if (!PAGE_LINKS_RAW_FILE.isFile()) // Read SQL and write cache
		{
			links = PageLinksList.readSqlFile(PAGE_LINKS_SQL_FILE, titleToId, idToTitle);
			PageLinksList.writeRawFile(links, PAGE_LINKS_RAW_FILE);
		}
		else  // Read cache
		{
			links = PageLinksList.readRawFile(PAGE_LINKS_RAW_FILE);
		}

		System.out.println("Done indexing.");

		ArrayList<Article> articles;

		if (!PAGERANK_FILE.isFile())
		{
			Pagerank pagerank = computePagerank(links);
			articles = writePagerankFile(pagerank, idToTitle);
		}
		else
		{
			articles = readPagerankFile(idToTitle.size());
		}

		System.out.println("Sorting pagerank...");
		articles.sort(new Comparator<Article>()
		{
			@Override
			public int compare(Article a1, Article a2)
			{
				int compare = Double.compare(a2.score, a1.score);
				return (compare != 0 ? compare : a1.title.compareTo(a2.title));
			}
		});
		System.out.println("Done sorting");

		writeSortedPagerankFile(articles);
	}

	// Function to compute the pagerank of all pages
	private static Pagerank computePagerank (int[] links)
	{
		System.out.println("Computing PageRank...");

		Pagerank pagerank = new Pagerank(links);

		long lastTimeMillis;

		// We iterate 200 times (enough to converge)
		for (int i = 0; i < PAGERANK_ITERATIONS; i++)
		{
			System.out.print("Iteration " + (i + 1) + " of " + PAGERANK_ITERATIONS);
			lastTimeMillis = System.currentTimeMillis();
			pagerank.computeScores();

			System.out.printf(" (%.3f s)%n", (System.currentTimeMillis() - lastTimeMillis) / 1000.0);
		}

		return pagerank;
	}

	// Function to write the pagerank in a txt file
	private static ArrayList<Article> writePagerankFile (Pagerank pagerank, Map<Integer, String> idToTitle)
	{
		ArrayList<Article> res = new ArrayList<>();

		Writer output = null;
		long startTime = System.currentTimeMillis();
		//on met try si jamais il y a une exception
		try
		{
			output = new BufferedWriter(new FileWriter(PAGERANK_FILE));

			long lastPrint = System.currentTimeMillis() - PRINT_INTERVAL;

			double[] scores = pagerank.getScores();
			int j = 0;
			for (int i = 0; i < scores.length; i++)
			{
				String title = idToTitle.get(i);
				if (title != null)
				{
					DecimalFormatSymbols symbols = new DecimalFormatSymbols();
					symbols.setDecimalSeparator('.');
					DecimalFormat format = new DecimalFormat("0.000", symbols);

					j++;

					double score = (double) Math.round((1 + (10 / Math.abs(Math.log10(scores[i])))) * 1000) / 1000;
					output.write(title + " \t " + format.format(score) + "\n");
					res.add(new Article(title, score));
				}

				if (System.currentTimeMillis() - lastPrint >= PRINT_INTERVAL)
				{
					System.out.printf("\rWriting %s: %.3f of %.3f million pages...", PAGERANK_FILE.getName(), j / 1000000.0, idToTitle.size() / 1000000.0);
					lastPrint = System.currentTimeMillis();
				}
			}
			System.out.printf("\rWriting %s: %.3f of %.3f million pages... Done (%.3f s)%n", PAGERANK_FILE.getName(), j / 1000000.0, idToTitle.size() / 1000000.0, (System.currentTimeMillis() - startTime) / 1000.0);
			output.flush();
		}
		catch(IOException ioe)
		{
			System.out.print("Erreur : ");
			ioe.printStackTrace();
		}
		finally
		{
			if (output != null)
			{
				try
				{
					output.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
		}

		return res;
	}

	// Function to read the txt file where the pagerank is stored
	private static ArrayList<Article> readPagerankFile (int length)
	{
		ArrayList<Article> res = new ArrayList<>();
		long startTime = System.currentTimeMillis();

		BufferedReader in = null;
		try
		{
			in = new BufferedReader(new InputStreamReader(new FileInputStream(PAGERANK_FILE), "UTF-8"));
			String line = in.readLine();
			long lastPrint = System.currentTimeMillis() - PRINT_INTERVAL;
			int i = 0;

			while (line != null)
			{
				i++;
				String[] split = line.split("\t");
				String title = split[0].trim();
				double score = Double.parseDouble(split[1].trim());
				res.add(new Article(title, score));
				line = in.readLine();

				if (System.currentTimeMillis() - lastPrint >= PRINT_INTERVAL)
				{
					System.out.printf("\rReading %s: %.3f of %.3f million pages...", PAGERANK_FILE.getName(), i / 1000000.0, length / 1000000.0);
					lastPrint = System.currentTimeMillis();
				}
			}
			System.out.printf("\rReading %s: %.3f of %.3f million pages... Done (%.3f s)%n", PAGERANK_FILE.getName(), i / 1000000.0, length / 1000000.0, (System.currentTimeMillis() - startTime) / 1000.0);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (in != null)
			{
				try
				{
					in.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
		}

		return res;
	}

	private static void writeSortedPagerankFile(ArrayList<Article> articles)
	{
		Writer output = null;
		long startTime = System.currentTimeMillis();
		//on met try si jamais il y a une exception
		try
		{
			output = new BufferedWriter(new FileWriter(SORTED_PAGERANK_FILE));

			long lastPrint = System.currentTimeMillis() - PRINT_INTERVAL;

			int i;
			for (i = 0; i < articles.size(); i++)
			{
				Article a = articles.get(i);

				DecimalFormatSymbols symbols = new DecimalFormatSymbols();
				symbols.setDecimalSeparator('.');
				DecimalFormat format = new DecimalFormat("0.000", symbols);

				output.write(a.title + " \t " + format.format(a.score) + "\n");

				if (System.currentTimeMillis() - lastPrint >= PRINT_INTERVAL)
				{
					System.out.printf("\rWriting %s: %.3f of %.3f million pages...", SORTED_PAGERANK_FILE.getName(), i / 1000000.0, articles.size() / 1000000.0);
					lastPrint = System.currentTimeMillis();
				}
			}
			System.out.printf("\rWriting %s: %.3f of %.3f million pages... Done (%.3f s)%n", SORTED_PAGERANK_FILE.getName(), i / 1000000.0, articles.size() / 1000000.0, (System.currentTimeMillis() - startTime) / 1000.0);
			output.flush();
		}
		catch(IOException ioe)
		{
			System.out.print("Erreur : ");
			ioe.printStackTrace();
		}
		finally
		{
			if (output != null)
			{
				try
				{
					output.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
		}
	}


	// Utility class to store a Wikipedia article with his title and his score after computing the pagerank
	private static class Article
	{
		private String title;
		private double score;

		public Article (String title, double score)
		{
			this.title = title;
			this.score = score;
		}
	}
		
}

package com.wikia.runescape;

import java.io.*;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;

import org.mediawiki.MediaWiki;

/**
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details. You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
public class ImageOptimisationBot {
	private static final Logger log = Logger.getLogger("com.wikia.runescape");

	private static long savedBytes;

	static {
		log.setLevel(Level.INFO);
	}

	/**
	 * @param args
	 *            unused
	 */
	public static void main(final String[] args) {
		// Read the bot's configuration file.
		final Settings settings = new Settings(new File(System.getProperty("user.home"), ".imgoptbot.conf"));
		// Require some things out of it from the start...
		{
			boolean fatalError = false;
			if (settings.getProperty("Wiki") == null) {
				log.log(Level.SEVERE, "$HOME/.imgoptbot.conf does not contain a value for Wiki, the wiki to work on");
				fatalError = true;
			}
			if (settings.getProperty("LoginName") == null) {
				log.log(Level.SEVERE, "$HOME/.imgoptbot.conf does not contain a value for LoginName, the username of the bot account on the wiki");
				fatalError = true;
			}
			if (settings.getProperty("LoginPassword") == null) {
				log.log(Level.SEVERE, "$HOME/.imgoptbot.conf does not contain a value for LoginPassword, the password of the bot account on the wiki");
				fatalError = true;
			}
			if (fatalError) {
				System.exit(1);
				return;
			}
		}

		// Which wiki are we working on?
		final MediaWiki wiki = new MediaWiki(settings.getProperty("Wiki"), settings.getProperty("ScriptPath", "")).setUsingCompression(true);

		loginLoop: while (true) { // LOGIN LOST LOOP
			while (true) {
				try {
					wiki.logIn(settings.getProperty("LoginName"), settings.getProperty("LoginPassword").toCharArray());
					break;
				} catch (final MediaWiki.LoginFailureException e) {
					log.log(Level.SEVERE, "Login failed; please check LoginName and LoginPassword in $HOME/.imgoptbot.conf", e);
					System.exit(1);
					return;
				} catch (final MediaWiki.LoginDelayException t) {
					log.log(Level.INFO, "Login throttled; retrying in {0} seconds", t.getWaitTime());
					try {
						Thread.sleep((long) t.getWaitTime() * 1000);
					} catch (InterruptedException e) {
						// don't care
					}
				} catch (final MediaWiki.BlockException b) {
					log.log(Level.SEVERE, "User blocked; please check its block log", b);
					System.exit(1);
					return;
				} catch (IOException e) {
					log.log(Level.SEVERE, "Network error occurred while logging in; retrying shortly", e);
					shortDelay();
				} catch (MediaWiki.MediaWikiException e) {
					log.log(Level.SEVERE, "Network error occurred while logging in; retrying shortly", e);
					shortDelay();
				}
			}

			/* MAIN BOT LOOP */
			while (true) {
				Iterator<MediaWiki.PageDesignation> pages = null;
				while (true) {
					try {
						pages = wiki.getPagesTranscluding("Template:Compression", false, MediaWiki.StandardNamespace.FILE);
						break;
					} catch (final Throwable t) {
						log.log(Level.INFO, "Network error occurred while getting transclusions of Template:Compression; retrying shortly", t);
						shortDelay();
					}
				}

				/*
				 * If a month has passed since the last optimisation of the
				 * entire wiki's PNG images, mark all revisions as done (but
				 * really skipped) and optimise the entire wiki instead.
				 */

				if (new Date().getTime() >= Long.parseLong(settings.getProperty("LastEntireWikiOptimisation", Long.toString(Long.MIN_VALUE))) + 30L * 86400 * 1000) {
					// ENTIRE WIKI (USE SPECIAL:ALLPAGES)
					log.log(Level.INFO, "Starting optimisation of supported images on the entire wiki");

					// Get Special:AllPages for the File namespace.
					Iterator<MediaWiki.ImageRevision> allFiles;
					while (true) {
						try {
							allFiles = wiki.getAllImages(null, null, true, 8192L, null, null);
							break;
						} catch (final Throwable t) {
							log.log(Level.WARNING, "Error occurred while getting Special:AllPages; retrying shortly", t);
							shortDelay();
						}
					}

					// Optimise ALL the images!
					while (true) {
						try {
							while (allFiles.hasNext()) {
								final MediaWiki.ImageRevision file = allFiles.next();

								try {
									optimize(wiki, settings, file.getFullPageName(), "(Automated) %C%s");
								} catch (final MediaWiki.PermissionException e) {
									log.log(Level.INFO, "Permission error occurred while optimising " + file.getFullPageName(), e);
									try {
										if (wiki.getCurrentUser().isAnonymous()) {
											log.log(Level.INFO, "Logged out; attempting to log back in shortly");
											shortDelay();
											continue loginLoop;
										} else {
											log.log(Level.INFO, "Trying another from Special:AllPages shortly");
											shortDelay();
										}
									} catch (Throwable t) {
										log.log(Level.INFO, "An error occurred while determining logged-in status; attempting to log in again shortly", e);
										shortDelay();
										continue loginLoop;
									}
								} catch (MediaWiki.BlockException be) {
									log.log(Level.SEVERE, "User blocked", be);
									System.exit(1);
									return;
								} catch (MediaWiki.ContentException ce) {
									log.log(Level.WARNING, "Content judged invalid for re-upload", ce);
									break;
								} catch (final Throwable t) {
									log.log(Level.INFO, "An error occurred while optimising " + file.getFullPageName() + "; trying another from Special:AllPages shortly", t);
									shortDelay();
								}
							}
							break;
						} catch (final MediaWiki.IterationException e) {
							log.log(Level.WARNING, "Error occurred while enumerating Special:AllPages; retrying shortly", e);
							shortDelay();
						}
					}

					// Mark the current timestamp as being the last run for the
					// entire wiki.
					settings.setProperty("LastEntireWikiOptimisation", Long.toString(new Date().getTime()));

					try {
						settings.store();
					} catch (final IOException e) {
						log.log(Level.WARNING, "Cannot write LastEntireWikiOptimisation to settings; the images on the entire wiki may be retried on the next bot run", e);
					}

					log.log(Level.INFO, "Optimisation of supported images on the entire wiki done");
				} else {
					log.log(Level.INFO, "Processing transclusions of Template:Compression");
					// TEMPLATE:COMPRESS INVOCATIONS
					while (true) {
						try {
							while (pages.hasNext()) {
								final MediaWiki.PageDesignation page = pages.next();
								// Do not bother to arrange continuing this in
								// case it's interrupted. The template
								// invocations that are skipped over can be
								// redone later.
								while (true) {
									try {
										optimize(wiki, settings, page.getFullPageName(), "(Semi-automated) %C%s requested via [[Template:Compression]]");
										break;
									} catch (final MediaWiki.PermissionException e) {
										log.log(Level.INFO, "Permission error occurred while optimising " + page.getFullPageName(), e);
										try {
											if (wiki.getCurrentUser().isAnonymous()) {
												log.log(Level.INFO, "Logged out; attempting to log back in shortly");
												shortDelay();
												continue loginLoop;
											} else {
												log.log(Level.INFO, "Permission error occurred while optimising an image; trying another from Template:Compression transclusions shortly");
												shortDelay();
											}
										} catch (Throwable t) {
											log.log(Level.INFO, "An error occurred while determining logged-in status; attempting to log in again shortly", e);
											shortDelay();
											continue loginLoop;
										}
									} catch (MediaWiki.BlockException be) {
										log.log(Level.SEVERE, "User blocked", be);
										System.exit(1);
										return;
									} catch (MediaWiki.ContentException ce) {
										log.log(Level.WARNING, "Content judged invalid for re-upload", ce);
										break;
									} catch (final Throwable t) {
										log.log(Level.INFO, "An error occurred while optimising " + page.getFullPageName() + "; trying another Template:Compression transclusion shortly", t);
										shortDelay();
									}
								}
							}
							break;
						} catch (final MediaWiki.IterationException ie) {
							log.log(Level.WARNING, "Error occurred while enumerating transclusions of Template:Compression; retrying shortly", ie);
							shortDelay();
						}
					}
				}

				// Whether all revisions are done or the entire wiki was just
				// optimised, wait for the next transclusion check.
				try {
					try {
						long seconds = Long.parseLong(settings.getProperty("RunInterval", "3600"));
						log.log(Level.INFO, "Transclusions of Template:Compression will be reprocessed in {0} seconds", seconds);
						Thread.sleep(seconds * 1000);
					} catch (final NumberFormatException e) {
						log.log(Level.WARNING, "Incorrect run interval; please check RunInterval in $HOME/.imgoptbot.conf (using 3600 seconds)");
						Thread.sleep(3600 * 1000);
					}
				} catch (final InterruptedException e) {
					// don't care
				}
			}
		}
	}

	/**
	 * Settings are Properties that automatically load and store themselves into
	 * files. Reads and writes ignore <tt>IOException</tt>s; the errors are
	 * logged to Java Logging instead.
	 */
	private static class Settings extends Properties {
		private static final long serialVersionUID = 1L;

		private final File file;

		public Settings(final File file) {
			this.file = file;
			try {
				final InputStream in = new FileInputStream(file);
				try {
					load(in);
				} finally {
					in.close();
				}
			} catch (final IOException e) {
				log.log(Level.WARNING, "Settings file cannot be read; using no settings at all", e);
			}
		}

		public void store() throws IOException {
			final OutputStream out = new FileOutputStream(file);
			try {
				store(out, null);
			} finally {
				out.close();
			}
		}
	}

	/**
	 * A cache of the last revisions of images seen by the bot.
	 * <p>
	 * The cache is stored in ${user.home}/.cache/pngoptbot using the canonical
	 * name of each file URL-encoded. This avoids path traversal vulnerabilities
	 * and allows certain characters to be used safely in filesystems
	 * prohibiting them.
	 */
	private static class RevisionCache {
		/**
		 * Returns the date and SHA-1 hash of the last revision of an image that
		 * has been seen by the bot.
		 * 
		 * @param wiki
		 *            The wiki to cache the file's last revision data for.
		 * @param fileNamespaceAndName
		 *            The full name, including the namespace, of the file.
		 * @return [0] the date, as a <code>java.util.Date</code><br />
		 *         [1] the SHA-1 hash, as a <code>byte[]</code>
		 */
		public static Object[] get(final MediaWiki wiki, final String fileNamespaceAndName) {
			final File cacheFile = new File(new File(new File(new File(System.getProperty("user.home"), ".cache"), "pngoptbot"), wiki.getHostName()), fileSystemName(fileNamespaceAndName));
			try {
				final ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile));
				try {
					return (Object[]) ois.readObject();
				} catch (final ClassNotFoundException e) {
					return null;
				} finally {
					ois.close();
				}
			} catch (final IOException e) {
				return null;
			}
		}

		public static void set(final MediaWiki wiki, final String fileNamespaceAndName, final Object[] timestamp) {
			final File cacheDirectory = new File(new File(new File(System.getProperty("user.home"), ".cache"), "pngoptbot"), wiki.getHostName());
			cacheDirectory.mkdirs();
			final File cacheFile = new File(cacheDirectory, fileSystemName(fileNamespaceAndName));
			try {
				final ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile));
				try {
					oos.writeObject(timestamp);
				} finally {
					oos.close();
				}
			} catch (final IOException e) {
				log.log(Level.WARNING, "Cannot write revision number to cache for {0}", fileNamespaceAndName);
			}
		}
	}

	private static void shortDelay() {
		try {
			Thread.sleep(45000);
		} catch (final InterruptedException e) {
			// don't care
		}
	}

	/**
	 * A PNG file starts with an 8-byte signature. The hexadecimal byte values
	 * are 89 50 4E 47 0D 0A 1A 0A; [...] ~Wikipedia
	 */
	private static final byte[] pngHeader = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	/**
	 * A GIF file starts with either "GIF87a" or "GIF89a" (
	 * <code>gif89aHeader</code>) in ASCII.
	 */
	private static final byte[] gif87aHeader = { 'G', 'I', 'F', '8', '7', 'a' };

	/**
	 * A GIF file starts with either "GIF87a" (<code>gif87aHeader</code>) or
	 * "GIF89a" in ASCII.
	 */
	private static final byte[] gif89aHeader = { 'G', 'I', 'F', '8', '9', 'a' };

	/**
	 * A JFIF file starts with JPEG Application Segment APP0. The header is
	 * divided as follows:
	 * <ul>
	 * <li>(Presumably) Segment start: 2 bytes. Is 0xFFD8, big-endian.
	 * <li>APP0 marker: 2 bytes. Is 0xFFE0, big-endian.
	 * <li>Segment length, excluding marker: 2 bytes. Is 0x0010, big-endian.
	 * <li>"JFIF" in ASCII, then a zero byte.
	 * </ul>
	 */
	private static final byte[] jfifHeader = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00 };

	/**
	 * An Exif file starts with JPEG Application Segment APP1. The header is
	 * divided as follows:
	 * <ul>
	 * <li>(Presumably) Segment start: 2 bytes. Is 0xFFD8, big-endian.
	 * <li>APP1 marker: 2 bytes. Is 0xFFE1, big-endian.
	 * <li>Segment length, excluding marker: 2 bytes. Is 0x0018, big-endian. (?)
	 * <li>"Exif" in ASCII, then a zero byte.
	 * </ul>
	 */
	private static final byte[] exifHeader = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x18, 'E', 'x', 'i', 'f', 0x00 };

	private static final int maxHeaderSize;

	static {
		maxHeaderSize = Math.max(Math.max(Math.max(Math.max(pngHeader.length, gif87aHeader.length), gif89aHeader.length), jfifHeader.length), exifHeader.length);
	}

	/**
	 * Reads, attempts to losslessly recompress, and uploads back to the wiki if
	 * the recompressed image is much smaller than the original, an image on a
	 * certain wiki.
	 * 
	 * @param wiki
	 *            The wiki to work on.
	 * @param settings
	 *            The settings to use for the optimisation.
	 * @param fileFullName
	 *            The name of the page, including its namespace, to optimise.
	 * @param editReasonFormat
	 *            The edit reason to use for the upload. %c%s (or %C%s, to
	 *            uppercase the first letter) in this string is replaced by
	 *            either <code>"PNG recompression and de-interlacing"</code>,
	 *            <code>"PNG recompression"</code> or
	 *            <code>"deinterlacing"</code>.
	 * @throws MediaWiki.MediaWikiException
	 *             if an API error occurs while reading from, or writing to, the
	 *             wiki
	 * @throws IOException
	 *             if an error occurs while reading from, or writing to, the
	 *             wiki
	 */
	private static void optimize(final MediaWiki wiki, final Settings settings, final String fileFullName, final String editReasonFormat) throws MediaWiki.MediaWikiException, IOException {
		if (Pattern.compile(settings.getProperty("BlockedFileRegex", "^$" /*- block nothing if the value is not there */)).matcher(fileFullName).find()) {
			log.log(Level.INFO, "{0} not optimised due to file name regex block", fileFullName);
			// "not optimised due to file name regex block" means
			// that someone tagged this image, but consensus is that
			// the image should not be automatically optimised, for
			// example because optimising an automatically-generated
			// image is a waste of time and looks like an edit war
			// between bots. Leave the tag in for it to be removed.
			return;
		}

		Object[] record = RevisionCache.get(wiki, fileFullName);
		Date dateLastSeen = record != null ? (Date) record[0] : null;

		MediaWiki.EditToken uploadToken = wiki.startUpload(wiki.getNamespaces().removeNamespacePrefix(fileFullName));

		Iterator<MediaWiki.ImageRevision> revisions = wiki.getImageRevisions(fileFullName, dateLastSeen != null ? new Date(dateLastSeen.getTime() + 1000) : null, null);

		MediaWiki.ImageRevision latest;
		try {
			if (!revisions.hasNext()) {
				log.log(Level.INFO, "{0} has no new revisions; ignoring", fileFullName);
				// "has no new revisions" means that someone placed the tag
				// after the last revision seen by this bot, and that revision
				// is still the last one. Whoever placed the tag thinks someone
				// else could improve on what the bot has already done. Leave
				// it on the page.
				return;
			}
			latest = revisions.next();
		} catch (MediaWiki.IterationException ie) {
			if (ie.getCause() instanceof IOException)
				throw (IOException) ie.getCause();
			else if (ie.getCause() instanceof MediaWiki.MediaWikiException)
				throw (MediaWiki.MediaWikiException) ie.getCause();
			else
				throw ie;
		}

		// Read the record for this file.
		byte[] sha1LastSeen = record != null ? (byte[]) record[1] : null;
		final Date wikiTimestamp = latest.getTimestamp();
		final byte[] wikiSha1 = hexToBytes(latest.getSHA1Hash());

		boolean removeCompressTag = true;
		// In the following line we avoid uploading a file if we are the user
		// who last uploaded it. This gets rid of retries for
		// obviously-optimised images.
		if (wikiTimestamp != null) {
			if (!latest.getUserName().equals(settings.getProperty("LoginName"))) {
				// If the image is newer on the wiki (wiki > lastseen)
				// and the SHA-1 doesn't match the last one we've seen...
				if (((dateLastSeen == null) || (wikiTimestamp.compareTo(dateLastSeen) > 0 /*- i.e. the wiki timestamps compares later than what we have on file */)) && !Arrays.equals(wikiSha1, sha1LastSeen)) {
					MediaWiki.Revision pageRevision = wiki.getLastRevision(fileFullName).next();
					if (!pageRevision.isContentHidden() && Pattern.compile(settings.getProperty("BlockedPageRegex", "^~$" /*- block only pages containing only "~" if the value is not there */)).matcher(pageRevision.getContent()).find()) {
						log.log(Level.INFO, "{0} not optimised due to page content regex block", fileFullName);
						// "not optimised due to page content regex block" means
						// that someone tagged this image for compression, but
						// some Image Maintenance templates placed on it still
						// need to be addressed. It would be a waste of time and
						// look like an edit war between the bot and any image
						// maintainers, not to mention that it would cause edit
						// conflicts. Leave the tag in for it to be removed.
						return;
					}

					/*
					 * Read content from the wiki. Also check its SHA-1 hash to
					 * detect corruption, and check for the presence of the PNG
					 * header. Everything is a bit mixed up, with the PNG
					 * detection, data copying and SHA-1 checks interleaved.
					 */
					log.log(Level.INFO, "Reading {0}", fileFullName);
					MessageDigest sha1 = null;
					try {
						sha1 = MessageDigest.getInstance("SHA1");
					} catch (NoSuchAlgorithmException e1) {
						log.log(Level.WARNING, "SHA-1 algorithm not present on this Java VM; file corruption will not be detected");
					}
					HeaderInputStream imageIn;
					try {
						imageIn = new HeaderInputStream(latest.getContent(), maxHeaderSize);
					} catch (EOFException eofe) {
						log.log(Level.WARNING, "{0} is too short to be a supported image file; ignoring", fileFullName);
						// If what we've read is the full file according to
						// the SHA-1 hash, cut off the compress tag as well.
						if (sha1 != null && Arrays.equals(sha1.digest(), wikiSha1)) {
							removeCompressTag(wiki, fileFullName);
						}
						return;
					}

					final boolean isPNG = Arrays.equals(imageIn.getHeader(pngHeader.length), pngHeader);
					final boolean isGIF87a = Arrays.equals(imageIn.getHeader(gif87aHeader.length), gif87aHeader);
					final boolean isGIF89a = Arrays.equals(imageIn.getHeader(gif89aHeader.length), gif89aHeader);
					final boolean isJFIF = Arrays.equals(imageIn.getHeader(jfifHeader.length), jfifHeader);
					final boolean isExif = Arrays.equals(imageIn.getHeader(exifHeader.length), exifHeader);

					long oldLength = 0;

					if (isPNG || isGIF87a || isGIF89a || isJFIF || isExif) {
						// Try to optimise the file.
						File localFile;
						FileOutputStream localFileOut;
						try {
							String extension = null; // make the compiler happy
							if (isPNG)
								extension = ".png";
							else if (isGIF87a || isGIF89a)
								extension = ".gif";
							else if (isJFIF || isExif)
								extension = ".jpg";

							// The extension is important for the advancecomp
							// tools, otherwise they can fail with a cryptic
							// "Missing signature" error 1 out of <large number>
							// times.
							localFile = File.createTempFile("pngoptbot-", extension);
							localFileOut = new FileOutputStream(localFile);
						} catch (final IOException e) {
							log.log(Level.SEVERE, "Cannot create a temporary file to hold the contents of " + fileFullName + " before optimisation; optimisation is cancelled for the file", e);
							return;
						}

						try {
							byte[] imageBuf = new byte[4096];
							int read;
							// Read from the wiki...
							while ((read = imageIn.read(imageBuf)) > 0) {
								oldLength += read;
								try {
									// ... and write to the local file.
									localFileOut.write(imageBuf, 0, read);
									if (sha1 != null)
										sha1.update(imageBuf, 0, read);
								} catch (final IOException e) {
									log.log(Level.SEVERE, "Cannot write to a temporary file to hold the contents of " + fileFullName + " before optimisation; optimisation is cancelled for the file", e);
									return;
								}
							}
							localFileOut.close();
							// Check for corruption.
							if (sha1 != null && !Arrays.equals(sha1.digest(), wikiSha1)) {
								log.log(Level.WARNING, "Data corruption detected for {0}; optimisation is cancelled for the file", fileFullName);
								return;
							}
							// Now, with the contents of the file stored on the
							// local filesystem, some tools may be launched on
							// it. The exact tools launched depend on the file
							// type; see below.
							String[] optimizationResult = { null, null };
							double minimumCompressionRatio = 0.0;
							long mandatorySize = 0;

							try {
								if (isPNG) {
									log.log(Level.INFO, "Attempting to optimise {0} ({1})", new Object[] { fileFullName, "PNG" });
									optimizationResult = optimizePNG(localFile);
									minimumCompressionRatio = Double.parseDouble(settings.getProperty("PNGMinimumCompressionRatio", "0"));
									mandatorySize = 53; // header,IHDR,IDAT,IEND
								} else if (isJFIF || isExif) {
									log.log(Level.INFO, "Attempting to optimise {0} ({1})", new Object[] { fileFullName, "JPEG" });
									optimizationResult = optimizeJPEG(localFile);
									minimumCompressionRatio = Double.parseDouble(settings.getProperty("JPEGMinimumCompressionRatio", "0"));
									mandatorySize = jfifHeader.length;
								} else if (isGIF87a || isGIF89a) {
									log.log(Level.INFO, "Attempting to optimise {0} ({1})", new Object[] { fileFullName, "GIF" });
									optimizationResult = optimizeGIF(localFile);
									minimumCompressionRatio = Double.parseDouble(settings.getProperty("GIFMinimumCompressionRatio", "0"));
									mandatorySize = 20; // header,palette,trailer
								}
							} catch (final IOException ioe) {
								log.log(Level.SEVERE, "Error occurred during local processing for " + fileFullName, ioe);
							}

							// Now look at the size of the file, and if it's
							// compressed by X% or more of the original size,
							// reupload it to the wiki. A special provision is
							// made to force a re-upload if the second value in
							// optimizationResult is non-null.
							final long newLength = localFile.length();
							final boolean sizeReducedEnough = newLength < oldLength && newLength >= mandatorySize && ((1.0 - (double) (newLength - mandatorySize) / (double) (oldLength - mandatorySize)) * 100.0 >= minimumCompressionRatio), forced = !sizeReducedEnough && optimizationResult[1] != null;
							if (sizeReducedEnough || forced) {
								try {
									log.log(Level.INFO, forced ? "Uploading a new version of {0} (forced: " + optimizationResult[1] + ")" : "Uploading a new version of {0}", fileFullName);
									final String editReason = forced ? optimizationResult[1] : optimizationResult[0];
									wiki.endUpload(uploadToken, new FileInputStream(localFile), String.format(editReasonFormat, editReason.charAt(0), editReason.substring(1)), null);
									log.log(Level.INFO, "Uploaded a new version of {0} ({1} -> {2} bytes)", new Object[] { fileFullName, oldLength, newLength });
								} catch (final MediaWiki.ProtectionException ce) {
									log.log(Level.WARNING, "Cannot upload to protected page " + fileFullName, ce);
									return;
								}
								savedBytes += oldLength - newLength;
								log.log(Level.INFO, "{0} bytes saved so far", savedBytes);
							} else
								log.log(Level.INFO, "{0} was not noticeably smaller when optimised", fileFullName);
						} finally {
							localFile.delete();
						}
					} else {
						// Alright, not a file recognised by this
						// bot, but another editor might be able to
						// optimise it somehow, so leave the tag
						log.log(Level.INFO, "{0} is not in a supported format; ignoring", fileFullName);
						removeCompressTag = false;
					}

					RevisionCache.set(wiki, fileFullName, new Object[] { wikiTimestamp, wikiSha1 });
				} else
					log.log(Level.INFO, "The last revision of {0} was already seen; ignoring", new Object[] { fileFullName, settings.getProperty("LoginName") });
			} else
				// "last uploaded by ME" means that it must be a supported
				// image, so remove the tag!
				log.log(Level.INFO, "{0} was last uploaded by {1}; ignoring", new Object[] { fileFullName, settings.getProperty("LoginName") });
		} else
			log.log(Level.INFO, "{0} has no revisions; ignoring", fileFullName);

		if (removeCompressTag)
			removeCompressTag(wiki, fileFullName);
	}

	protected static String[] optimizePNG(final File localFile) throws IOException {
		boolean interlaced = false;
		{
			RandomAccessFile localFileIn = new RandomAccessFile(localFile, "r");
			try {
				localFileIn.seek(0x1C);
				interlaced = localFileIn.readBoolean();
			} finally {
				localFileIn.close();
			}
		}
		// optipng:
		// a) -zc9 (compression levels to try)
		// b) -zm8 (memory level, 9 does not improve over 8)
		// c) -zs0-3 (zlib strategies to try, see zlib docs)
		// d) -f0-5 (PNG filters to try, see PNG docs)
		// e) -i0 (deinterlace interlaced PNG images)
		// advpng:
		// a) -z4 (perform the highest level of PNG-specific
		// optimisations)
		// advdef:
		// a) -z4 (perform the highest level of
		// deflate-specific optimisations)
		final String[][] toolCommandLines = { { "optipng", "-q", "-zc9", "-zm8", "-zs0-3", "-f0-5", "-i0", localFile.toString() }, { "advpng", "-q", "-z4", localFile.toString() }, { "advdef", "-q", "-z4", localFile.toString() } };
		for (final String[] toolCommandLine : toolCommandLines) {
			if (!runProcess(toolCommandLine))
				return new String[] { null, null };
		}
		return new String[] { interlaced ? "PNG recompression and de-interlacing" : "PNG recompression", interlaced ? "De-interlacing" : null };
	}

	protected static String[] optimizeJPEG(final File localFile) throws IOException {
		final String[][] toolCommandLines = { { "jpegoptim", "-q", "--strip-all", localFile.toString() } };
		for (final String[] toolCommandLine : toolCommandLines) {
			if (!runProcess(toolCommandLine))
				return new String[] { null, null };
		}
		return new String[] { "JPEG lossless recompression", null };
	}

	protected static String[] optimizeGIF(final File localFile) throws IOException {
		final String[][] toolCommandLines = { { "gifsicle", "-b", "-O3", "--no-interlace", "--no-comments", "--no-names", localFile.toString() } };
		for (final String[] toolCommandLine : toolCommandLines) {
			if (!runProcess(toolCommandLine))
				return new String[] { null, null };
		}
		return new String[] { "GIF animation optimisation", null };
	}

	protected static boolean runProcess(String[] toolCommandLine) {
		try {
			final Process p = Runtime.getRuntime().exec(toolCommandLine);
			try {
				// Read and display the stdout and stderr outputs. This also
				// prevents deadlock on some systems if the streams are not
				// read.
				StreamReader stdOutReader = new StreamReader(p.getInputStream());
				Thread stdOutReaderThread = new Thread(stdOutReader, toolCommandLine[0] + " stdout reader");
				stdOutReaderThread.setDaemon(true);
				stdOutReaderThread.start();
				StreamReader stdErrReader = new StreamReader(p.getErrorStream());
				Thread stdErrReaderThread = new Thread(stdErrReader, toolCommandLine[0] + " stderr reader");
				stdErrReaderThread.setDaemon(true);
				stdErrReaderThread.start();
				try {
					p.waitFor();
				} finally {
					stdOutReaderThread.interrupt();
					stdErrReaderThread.interrupt();
					stdOutReaderThread.join();
					String stdOut = stdOutReader.toString();
					if (stdOut.length() > 0)
						log.log(Level.INFO, "{0}''s standard output follows:\n{1}", new Object[] { toolCommandLine[0], stdOut });
					stdErrReaderThread.join();
					String stdErr = stdErrReader.toString();
					if (stdErr.length() > 0)
						log.log(Level.WARNING, "{0}''s standard error follows:\n{1}", new Object[] { toolCommandLine[0], stdErr });
				}
			} finally {
				// Work around various resource leaks.
				p.getOutputStream().close();
				p.getErrorStream().close();
				p.getInputStream().close();
				p.destroy();
			}
			boolean normalExit = p.exitValue() == 0;
			if (!normalExit)
				log.log(Level.WARNING, "{0} exited with status {1}", new Object[] { toolCommandLine[0], p.exitValue() });
			return normalExit;
		} catch (final IOException e) {
			log.log(Level.SEVERE, "Cannot launch {0}! Make sure it is installed and appears in your PATH environment variable!", toolCommandLine[0]);
			return false;
		} catch (final InterruptedException e) {
			log.log(Level.WARNING, "Received java.lang.InterruptedException while executing {0}; optimisation is cancelled for the file", toolCommandLine[0]);
			return false;
		}
	}

	private static final String compressTagRegexPart = "\\{\\{(?:[tT]emplate:)?[cC]ompress(?:ion)?\\}\\}";

	private static final Pattern compressTagRemover = Pattern.compile("^" + compressTagRegexPart + "\\s*|\\s*" + compressTagRegexPart);

	protected static void removeCompressTag(MediaWiki wiki, String fileFullName) throws MediaWiki.BlockException {
		while (true) {
			try {
				MediaWiki.EditToken editToken = wiki.startEdit(fileFullName);
				// Remove the {{compress}} tag from the description page.
				Iterator<MediaWiki.Revision> descriptionPageRevisions = wiki.getLastRevision(true, fileFullName);
				if (descriptionPageRevisions.hasNext()) {
					MediaWiki.Revision descriptionPageRevision = descriptionPageRevisions.next();
					if (descriptionPageRevision == null)
						throw new MediaWiki.MissingPageException(fileFullName);
					String oldText = descriptionPageRevision.getContent(), newText = compressTagRemover.matcher(oldText).replaceAll("");
					if (!oldText.equals(newText)) {
						wiki.replacePage(editToken, newText, "-Template:Compression", true /*- bot */, true /*- minor */);
						log.log(Level.INFO, "Successfully edited {0}", fileFullName);
					}
				}
				break;
			} catch (final MediaWiki.ActionDelayException e) {
				log.log(Level.WARNING, "Edition of " + fileFullName + " delayed", e);
				shortDelay();
				// retry later
			} catch (final MediaWiki.ConflictException shouldNotHappen) {
				log.log(Level.WARNING, "Received a conflict while editing " + fileFullName + "; retrying", shouldNotHappen);
				// retry immediately
			} catch (final MediaWiki.BlockException e) {
				// fatal
				throw e;
			} catch (final IOException ioe) {
				log.log(Level.WARNING, "Network error occurred while editing " + fileFullName + "; retrying shortly", ioe);
				shortDelay();
				// retry later
			} catch (final MediaWiki.MediaWikiException mwe) {
				log.log(Level.WARNING, "Edition of " + fileFullName + " failed", mwe);
				break;
			}
		}
	}

	protected static String fileSystemName(final String name) {
		try {
			final StringBuilder nameBuffer = new StringBuilder(name);
			if (nameBuffer.length() > 0) {
				// Uppercase the first letter of the file name.
				nameBuffer.setCharAt(0, Character.toUpperCase(nameBuffer.charAt(0)));
				// If this is a namespaced name, the namespace's first letter
				// was uppercased above instead. Now uppercase the first letter
				// of the file name.
				final int colonIndex = nameBuffer.indexOf(":");
				if ((colonIndex != -1) && (nameBuffer.length() > colonIndex + 1)) {
					nameBuffer.setCharAt(colonIndex + 1, Character.toUpperCase(nameBuffer.charAt(colonIndex + 1)));
				}
			}
			return URLEncoder.encode(nameBuffer.toString().replace(' ', '_'), "UTF-8");
		} catch (final UnsupportedEncodingException shouldNeverHappen) {
			throw new InternalError("UTF-8 is not supported by the Java VM");
		}
	}

	protected static byte[] hexToBytes(String hashHex) {
		if (hashHex.length() % 2 == 1)
			throw new IllegalArgumentException("hashHex must have an even number of hexits");
		final byte[] result = new byte[hashHex.length() / 2];
		for (int i = 0; i < result.length; i++) {
			result[i] = (byte) Short.parseShort(hashHex.substring(i * 2, (i + 1) * 2), 16);
		}
		return result;
	}

	/**
	 * <tt>HeaderInputStream</tt> reads and keeps a small buffer containing a
	 * file's header so that it can be recalled an unlimited number of times, as
	 * well as returning it as part of normal <code>read</code> calls. For
	 * example, if the header preserved by this class is <code>"PNG"</code>, the
	 * first <code>read</code> call will return the <code>'P'</code> from the
	 * header.
	 */
	private static class HeaderInputStream extends InputStream {
		private final InputStream inner;

		private final byte[] header;

		/**
		 * This is the next location to be read inside the header, or
		 * <code>header.length</code> if reads should be serviced from
		 * <code>inner</code>.
		 */
		private int loc = 0;

		/**
		 * Initialises a <tt>HeaderInputStream</tt> that takes its bytes from
		 * the specified <code>inner</code> <tt>InputStream</tt> and preserves a
		 * header of <code>headerSize</code> bytes.
		 * 
		 * @param inner
		 *            The source of bytes for the newly-created
		 *            <tt>HeaderInputStream</tt>.
		 * @param headerSize
		 *            The size of the header to be preserved by the
		 *            newly-created <tt>HeaderInputStream</tt>.
		 * @throws IOException
		 *             if reading in enough bytes to fill the header throws
		 *             <tt>IOException</tt>
		 */
		public HeaderInputStream(InputStream inner, int headerSize) throws IOException {
			this.inner = inner;
			header = new byte[headerSize];
			int read, pos = 0;
			while (pos < header.length && (read = inner.read(header, pos, header.length - pos)) > 0)
				pos += read;
		}

		@Override
		public int read() throws IOException {
			if (loc < header.length) {
				loc++;
				return header[loc - 1] & 0xFF;
			} else
				return inner.read();
		}

		@Override
		public long skip(long n) throws IOException {
			if (n <= 0)
				return 0;

			long headerSkipped = 0;
			if (loc < header.length) {
				int oldLoc = loc;
				loc += Math.min(n, header.length - oldLoc);
				headerSkipped = loc - oldLoc;
				n -= headerSkipped;
			}

			if (n > 0)
				return headerSkipped + inner.skip(n);
			else
				return headerSkipped;
		}

		@Override
		public int available() throws IOException {
			return (header.length - loc) + inner.available();
		}

		@Override
		public void close() throws IOException {
			inner.close();
		}

		@Override
		public int read(byte[] b) throws IOException {
			return read(b, 0, b.length);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (loc < header.length) {
				int copylen = Math.min(len, header.length - loc);
				System.arraycopy(header, loc, b, off, copylen);
				loc += copylen;
				return copylen;
			} else
				return inner.read(b, off, len);
		}

		/**
		 * Returns the first <code>n</code> preserved header bytes.
		 * 
		 * @param n
		 *            The number of bytes to retrieve.
		 * @return the bytes requested
		 */
		public byte[] getHeader(int n) {
			final byte[] result = new byte[n];
			System.arraycopy(header, 0, result, 0, n);
			return result;
		}
	}

	private static class StreamReader implements Runnable {
		private final InputStream in;

		private final StringBuilder sb = new StringBuilder();

		public StreamReader(final InputStream in) {
			this.in = in;
		}

		public void run() {
			InputStreamReader inChars = new InputStreamReader(in);
			char[] buffer = new char[1024];
			int read;
			try {
				while ((read = inChars.read(buffer)) > 0) {
					sb.append(buffer, 0, read);
				}
			} catch (final IOException ioe) {
				// eat, or maybe report
			}
		}

		@Override
		public String toString() {
			return sb.toString();
		}
	}
}

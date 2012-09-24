mediawikitools
==============

Java tools using the MediaWiki API. Some of these are used on <http://runescape.wikia.com/>, but others are more general and can be used on any MediaWiki wiki.

Compiling
---------

To compile Java files, you must have a Java development kit. There is the [OpenJDK] [openjdk] and [Oracle's JDK] [oraclejdk].

You can use [Eclipse] [eclipse] or any other Java IDE of your choosing, with the Java development kit installed, to compile these files. There's already an Eclipse project description in the repository; feel free to make a pull request if you create a project for another IDE.

You can also use the included *build.xml* file with [Apache Ant] [ant] installed, as follows:

    user@host ~/mediawikitools $ ant

[eclipse]: http://www.eclipse.org/ "Eclipse integrated development environment"
[ant]: http://ant.apache.org/ "Apache Ant"
[openjdk]: http://openjdk.java.net/install/ "Installing the OpenJDK"
[oraclejdk]: http://www.oracle.com/technetwork/java/javase/downloads/index.html "Installing Oracle's Java runtime and development kit"

Function of each file
---------------------

After compiling all of the files, you can run the programs.

## ImageOptimisationBot.java

Used on <http://runescape.wikia.com/>, this bot continuously runs and does a monthly pass on all the files on a wiki, as well as an hourly pass on the files whose description pages transclude *Template:Compression*. You can run this as follows:

    user@host ~/mediawikitools $ java com.wikia.runescape.ImageOptimisationBot

Documentation for this bot can be found [here](http://runescape.wikia.com/wiki/User:Image_optimisation_bot/Source).

## ClanExpGainScraper.java

Used on <http://runescape.wikia.com/>, this bot runs when invoked (or scheduled to be invoked) and writes a column in a table of experience for a [RuneScape] [runescape] clan. You can run this as follows:

    user@host ~/mediawikitools $ java com.wikia.runescape.ClanExpGainScraper

Documentation for this bot can be found [here](http://runescape.wikia.com/wiki/User:A_proofbot/Source).

[runescape]: http://www.runescape.com/ "RuneScape, an MMO by Jagex Ltd."

## WikiShell.java

This wiki command-line tool allows you to read pages, get information about many types of objects (users, pages, categories and so on) and read page histories, as well as edit, create, protect, move and delete pages, assign user rights, and more. You can run this as follows:

    user@host ~/mediawikitools $ java org.mediawiki.WikiShell [<wiki host or IP>] [<port>]

Documentation for this shell can be found in the shell itself. Invoke the `commands` command, then use `help <command name>` to know more about one command.

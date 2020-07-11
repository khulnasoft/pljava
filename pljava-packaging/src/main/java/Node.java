/*
 * Copyright (c) 2015-2020 Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Chapman Flack (this file, 2020)
 *   PostgreSQL Global Development Group, Michael Paquier, Alvaro Herrera
 *    (PostgresNode.pm, 2015, of which similar methods here are ports)
 */
package org.postgresql.pljava.packaging;

import org.gjt.cuspy.JarX;

import java.io.InputStream;

import static java.lang.System.getProperty;
import static java.lang.System.setProperty;

import java.nio.ByteBuffer;
import static java.nio.charset.Charset.defaultCharset;

import java.util.regex.Matcher;
import static java.util.regex.Pattern.compile;

/*
 * For "Node" behavior:
 */


import static java.lang.ProcessBuilder.Redirect.INHERIT;
import static java.lang.Thread.interrupted;

import static java.net.InetAddress.getLoopbackAddress;
import java.net.ServerSocket;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.file.attribute.PosixFilePermissions;
import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.lines;
import static java.nio.file.Files.walk;
import static java.nio.file.Files.write;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import java.nio.file.NoSuchFileException;

import java.sql.Connection;
import static java.sql.DriverManager.getConnection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;

import javax.sql.rowset.RowSetProvider;
import javax.sql.rowset.WebRowSet;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.Properties;
import java.util.Random;
import java.util.Spliterator;
import static java.util.Spliterator.IMMUTABLE;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import java.util.jar.JarFile;

import java.util.stream.Stream;
import static java.util.stream.StreamSupport.stream;

/**
 * Subclass the JarX extraction tool to provide a {@code resolve} method that
 * replaces prefixes {@code pljava/foo/} in path names stored in the archive
 * with the result of {@code pg_config --foo}.
 *<p>
 * As this represents a second extra {@code .class} file that has to be added
 * to the installer jar anyway, it will also contain some methods intended to be
 * useful for tasks related to installation and testing. The idea is not to go
 * overboard, but supply a few methods largely modeled on the most basic ones of
 * PostgreSQL's {@code PostgresNode.pm}, with the idea that they can be invoked
 * from {@code jshell} if its classpath includes the installer jar (and
 * pgjdbc-ng).
 *<p>
 * Unlike the many capabilities of {@code PostgresNode.pm}, this only deals in
 * TCP sockets bound to {@code localhost} (Java doesn't have Unix sockets out of
 * the box yet) and only a few of the most basic operations.
 *<p>
 * As in JarX itself, some liberties with coding style may be taken here to keep
 * this one extra {@code .class} file from proliferating into a bunch of them.
 */
public class Node extends JarX {

	private Matcher m_prefix;
	private int m_fsepLength;
	private boolean m_dryrun = false;

	private static Node s_jarxHelper = new Node(null, 0, null, null);
	private static boolean s_jarProcessed = false;
	private static String s_examplesJar;

	public static void main(String[] args) throws Exception
	{
		if ( args.length > 0 )
		{
			System.err.println("usage: java -jar filename.jar");
			System.exit(1);
		}

		s_jarxHelper.extract();
	}

	/**
	 * Extract the jar contents, just as done in the normal case of running
	 * this class with {@code java -jar}.
	 *<p>
	 * Only to be called on the singleton instance {@code s_jarxHelper}.
	 *<p>
	 * For a version that doesn't really extract anything, but still primes the
	 * {@code resolve} method to know where things <em>should be</em> extracted,
	 * see {@link #dryExtract}.
	 */
	@Override
	public void extract() throws Exception
	{
		super.extract();
		s_jarProcessed = true;
	}

	/**
	 * Prepare the resolver, ignoring the passed string (ordinarily a script or
	 * rules); this resolver's rules are hardcoded.
	 */
	@Override
	public void prepareResolver(String v) throws Exception
	{
		m_prefix = compile("^pljava/([^/]+dir)(?![^/])").matcher("");
		m_fsepLength = getProperty("file.separator").length();
	}

	@Override
	public String resolve(String storedPath, String platformPath)
	throws Exception
	{
		if ( m_prefix.reset(storedPath).lookingAt() )
		{
			int prefixLength = m_prefix.end();
			String key = m_prefix.group(1);
			String propkey = "pgconfig." + key;
			String replacement = getProperty(propkey);
			if ( null == replacement )
			{
				String pgc = getProperty("pgconfig", "pg_config");
				ProcessBuilder pb = new ProcessBuilder(pgc, "--"+key);
				pb.redirectError(ProcessBuilder.Redirect.INHERIT);
				Process proc = pb.start();
				byte[] output;
				try ( InputStream instream = proc.getInputStream() )
				{
					proc.getOutputStream().close();
					output = instream.readAllBytes();
				}
				finally
				{
					int status = proc.waitFor();
					if ( 0 != status )
					{
						System.err.println(
							"ERROR: pg_config status is "+status);
						System.exit(1);
					}
				}
				/*
				 * pg_config output is the saved value followed by one \n only.
				 */
				replacement = defaultCharset().newDecoder()
					.decode(ByteBuffer.wrap(output, 0, output.length - 1))
					.toString();
				setProperty(propkey, replacement);
			}
			int plen = m_fsepLength - 1; /* original separator had length 1 */
			plen += prefixLength;
			replacement += platformPath.substring(plen);
			if ( ! m_dryrun )
				return replacement;
			if ( -1 != storedPath.indexOf("/pljava-examples-") )
				s_examplesJar = replacement;
			return null;
		}

		System.err.println("WARNING: extraneous jar entry not extracted: "
			+ storedPath);
		return null;
	}

	/*
	 * Members below this point represent the state and behavior of an instance
	 * of this class that is acting as a "Node" rather than as the JarX helper.
	 */

	/**
	 * Name of a "Node"; null for an ordinary Node instance.
	 */
	private final String m_name;

	/**
	 * A TCP port on {@code localhost} that was free when {@code get_new_node}
	 * was called, and is likeliest to still be free if {@code start} is then
	 * called without undue delay.
	 */
	private final int m_port;

	/**
	 * A temporary base directory chosen and created in {@code java.io.tmpdir}
	 * by {@code get_new_node} and removed by {@code clean_node}.
	 */
	private final Path m_basedir;

	/**
	 * A password generated at {@code get_new_node} time, and used by
	 * {@code init} as the database-superuser password passed to {@code initdb}.
	 */
	private final String m_password;

	/**
	 * The server process after a successful {@code start}; null again after a
	 * successful {@code stop}.
	 */
	private Process m_server;

	/**
	 * Identifying information for a "node" instance, or for the singleton
	 * extractor instance.
	 */
	@Override
	public String toString()
	{
		if ( null == m_name )
			return "Extractor instance";
		return "\"Node\": " + m_name;
	}

	/**
	 * Construct an instance; all nulls for the parameters are passed by the
	 * static initializer to make the singleton extractor instance, and any
	 * other instance is constructed by {@code get_new_node} for controlling
	 * a PostgreSQL instance.
	 */
	private Node(String nodeName, int port, Path basedir, String password)
	{
		m_name = nodeName;
		m_port = port;
		m_basedir = basedir;
		m_password = password;
	}

	/**
	 * Return a new {@code Node} that can be used to initialize and start a
	 * PostgreSQL instance.
	 *<p>
	 * Establishes a VM shutdown hook that will stop the server (if started)
	 * and recursively remove the <em>basedir</em> before the VM exits.
	 */
	public static Node get_new_node(String name) throws Exception
	{
		byte[] pwbytes = new byte [ 6 ];
		new Random().nextBytes(pwbytes);
		Node n = new Node(
			requireNonNull(name),
			get_free_port(),
			createTempDirectory(
				"t_pljava_" + name + "_data",
				PosixFilePermissions.asFileAttribute(
					PosixFilePermissions.fromString("rwx------"))),
				Base64.getEncoder().encodeToString(pwbytes));
		Thread t =
			new Thread(() ->
				{
					try
					{
						n.stop();
						n.clean_node();
					}
					catch ( Exception e )
					{
						e.printStackTrace();
					}
				}, "Node " + name + " shutdown");
		Runtime.getRuntime().addShutdownHook(t);
		return n;
	}

	/**
	 * Return a TCP port on the loopback interface that is free at the moment
	 * this method is called.
	 */
	public static int get_free_port() throws Exception
	{
		try (ServerSocket s = new ServerSocket(0, 0, getLoopbackAddress()))
		{
			return s.getLocalPort();
		}
	}

	/**
	 * Recursively remove the <em>basedir</em> and its descendants.
	 */
	public void clean_node() throws Exception
	{
		clean_node(false);
	}

	/**
	 * Recursively remove the <em>basedir</em> and its descendants.
	 * @param keepRoot if true, the descendants are removed, but not the basedir
	 * itself.
	 */
	public void clean_node(boolean keepRoot) throws Exception
	{
		/*
		 * How can Java *still* not have a deleteTree()?
		 */
		ArrayDeque<Path> stk = new ArrayDeque<>();
		for ( Path p : (Iterable<Path>)walk(m_basedir)::iterator )
		{
			while ( ! stk.isEmpty()  &&  ! p.startsWith(stk.peek()) )
				deleteIfExists(stk.pop());
			stk.push(p);
		}
		if ( keepRoot )
			stk.pollLast();
		for ( Path p : stk )
			deleteIfExists(p);
	}

	/**
	 * Process the jar without really extracting, to compute the path mappings.
	 */
	private static void dryExtract() throws Exception
	{
		if ( s_jarProcessed )
			return;
		try
		{
			s_jarxHelper.m_dryrun = true;
			s_jarxHelper.extract();
		}
		finally
		{
			s_jarxHelper.m_dryrun = false;
		}
	}

	/**
	 * Given a path from the archive, or any path <em>resembling</em> one in
	 * the archive (that is, always {@code /} as the separator, and starting
	 * with {@code pljava/}<em>key</em> where {@code --}<em>key</em> is known
	 * to {@code pg_config}, return the platform-specific path where it would
	 * be installed.
	 */
	private static String resolve(String archivePath) throws Exception
	{
		return s_jarxHelper.resolve(
			archivePath, Paths.get("", archivePath.split("/")).toString());
	}

	/**
	 * Return the directory name to be used as the PostgreSQL data directory
	 * for this node.
	 */
	public Path data_dir()
	{
		return m_basedir.resolve("pgdata");
	}

	/**
	 * Like {@code init()} but returns an {@code AutoCloseable} that will
	 * recursively remove the files and directories under the <em>basedir</em>
	 * (but not the <em>basedir</em> itself) on the exit of a calling
	 * try-with-resources scope.
	 */
	public AutoCloseable initialized_cluster() throws Exception
	{
		return initialized_cluster(Map.of());
	}

	/**
	 * Like {@code init()} but returns an {@code AutoCloseable} that will
	 * recursively remove the files and directories under the <em>basedir</em>
	 * (but not the <em>basedir</em> itself) on the exit of a calling
	 * try-with-resources scope.
	 */
	public AutoCloseable initialized_cluster(Map<String,String> suppliedOptions)
	throws Exception
	{
		init(suppliedOptions);
		return () ->
		{
			clean_node(true);
		};
	}

	/**
	 * Invoke {@code initdb} for the node, passing default options appropriate
	 * for this setting.
	 */
	public void init() throws Exception
	{
		init(Map.of());
	}

	/**
	 * Invoke {@code initdb} for the node, with <em>suppliedOptions</em>
	 * overriding or supplementing the ones that would be passed by default.
	 *<p>
	 * By default, {@code postgres} will be the name of the superuser, UTF-8
	 * will be the encoding, {@code auth-local} will be {@code peer} and
	 * {@code auth-host} will be {@code md5}. The initialization will skip
	 * {@code fsync} for speed rather than safety (if something goes wrong, just
	 * {@code clean_node()} and start over).
	 *<p>
	 * The {@code initdb} that will be run is the one in the {@code bindir}
	 * reported by {@code pg_config} (or set by {@code -Dpgconfig.bindir}).
	 * @param suppliedOptions a Map where each key is an option to initdb
	 * (for example, --encoding), and the value corresponds.
	 */
	public void init(Map<String,String> suppliedOptions) throws Exception
	{
		dryExtract();
		/*
		 * For extract/install purposes, there is already a resolve() method
		 * that expands keys like pljava/bindir to pg_config --bindir output.
		 */
		String initdb = resolve("pljava/bindir/initdb");
		Path pwfile = createTempFile(m_basedir, "pw", "",
			PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-------")));

		Map<String,String> options = new HashMap(suppliedOptions);
		options.putIfAbsent("--pgdata", data_dir().toString());
		options.putIfAbsent("--username", "postgres");
		options.putIfAbsent("--encoding", "utf-8");
		options.putIfAbsent("--pwfile", pwfile.toString());
		options.putIfAbsent("--auth-local", "peer");
		options.putIfAbsent("--auth-host", "md5");
		options.putIfAbsent("-N", null);

		String[] args =
			Stream.concat(
				Stream.of(initdb),
				options.entrySet().stream()
				.flatMap(e ->
					null == e.getValue()
					? Stream.of(e.getKey())
					: Stream.of(e.getKey(), e.getValue()))
			)
			.toArray(String[]::new);

		try
		{
			write(pwfile, List.of(m_password), US_ASCII);
			ProcessBuilder pb =
				new ProcessBuilder(args)
				.redirectOutput(INHERIT)
				.redirectError(INHERIT);
			Process p = pb.start();
			p.getOutputStream().close();
			if ( 0 != p.waitFor() )
				throw new AssertionError(
					"Nonzero initdb result: " + p.waitFor());
		}
		finally
		{
			deleteIfExists(pwfile);
		}
	}

	/**
	 * Like {@code start()} but returns an {@code AutoCloseable} that will
	 * stop the server on the exit of a calling try-with-resources scope.
	 */
	public AutoCloseable started_server() throws Exception
	{
		return started_server(Map.of());
	}

	/**
	 * Like {@code start()} but returns an {@code AutoCloseable} that will
	 * stop the server on the exit of a calling try-with-resources scope.
	 */
	public AutoCloseable started_server(Map<String,String> suppliedOptions)
	throws Exception
	{
		start(suppliedOptions);
		return () ->
		{
			stop();
		};
	}

	/**
	 * Start a PostgreSQL server for the node, passing default options
	 * appropriate for this setting.
	 */
	public void start() throws Exception
	{
		start(Map.of());
	}

	/**
	 * Start a PostgreSQL server for the node, with <em>suppliedOptions</em>
	 * overriding or supplementing the ones that would be passed by default.
	 *<p>
	 * By default, the server will listen only on the loopback interface and
	 * not on any Unix-domain socket, on the port selected when this Node was
	 * created, and for a maximum of 16 connections. Its cluster name will be
	 * the name given to this Node, and fsync will be off to favor speed over
	 * durability.
	 *<p>
	 * The server that will be run is the one in the {@code bindir}
	 * reported by {@code pg_config} (or set by {@code -Dpgconfig.bindir}).
	 *<p>
	 * If the server is PostgreSQL 10 or later, it is definitely ready to accept
	 * connections when this method returns. If not, it is highly likely to be
	 * ready, but no test connection has been made to confirm it.
	 * @param suppliedOptions a Map where the key is a configuration variable
	 * name as seen in {@code postgresql.conf} or passed to the server with
	 * {@code -c} and the value corresponds.
	 */
	public void start(Map<String,String> suppliedOptions) throws Exception
	{
		if ( null != m_server  &&  m_server.isAlive() )
			throw new IllegalStateException(
				"node \"" + m_name + "\" is already running");

		dryExtract();
		String postgres = resolve("pljava/bindir/postgres");

		Map<String,String> options = new HashMap(suppliedOptions);
		options.putIfAbsent("data_directory", data_dir().toString());
		options.putIfAbsent("listen_addresses",
			getLoopbackAddress().getHostAddress());
		options.putIfAbsent("port", "" + m_port);
		options.putIfAbsent("unix_socket_directories", "");
		options.putIfAbsent("max_connections", "16");
		options.putIfAbsent("fsync", "off");
		options.putIfAbsent("cluster_name", m_name);

		String[] args =
			Stream.concat(
				Stream.of(postgres),
				options.entrySet().stream()
				.flatMap(e ->
					"data_directory".equals(e.getKey())
					? Stream.of("-D", e.getValue())
					: Stream.of("-c", e.getKey() + "=" + e.getValue()))
			)
			.toArray(String[]::new);

		ProcessBuilder pb =
			new ProcessBuilder(args)
			.redirectOutput(INHERIT)
			.redirectError(INHERIT);
		Process p = pb.start();
		p.getOutputStream().close();
		try
		{
			wait_for_pid_file(p);
			m_server = p;
		}
		finally
		{
			if ( m_server == p )
				return;
			if ( p.isAlive() )
				p.destroy();
		}
	}

	/**
	 * Stop the server instance associated with this Node.
	 *<p>
	 * No effect if it has not been started or has already been stopped, but
	 * a message to standard error is logged if the server had been started and
	 * the process is found to have exited unexpectedly.
	 */
	public void stop() throws Exception
	{
		if ( null == m_server )
			return;
		if ( m_server.isAlive() )
		{
			m_server.destroy();
			m_server.waitFor();
			m_server = null;
			return;
		}
		System.err.println("Server had already exited with status " +
			m_server.exitValue());
		m_server = null;
	}

	/**
	 * Return a {@code Connection} to the server associated with this Node,
	 * as the database superuser, using the generated password.
	 */
	public Connection connect() throws Exception
	{
		String url = "jdbc:pgsql://localhost:" + m_port + "/postgres";
		Properties p = new Properties();
		p.setProperty("user", "postgres");
		p.setProperty("password", m_password);
		return getConnection(url, p);
	}

	/**
	 * Set a configuration variable on the server.
	 *<p>
	 * This deserves a convenience method because the most familiar PostgreSQL
	 * syntax for SET doesn't lend itself to parameterization.
	 * @return a {@link #resultStream resultStream} from executing the statement
	 */
	public static Stream<Object> setConfig(
		Connection c, String settingName, String newValue, boolean isLocal)
	throws Exception
	{
		PreparedStatement ps =
			c.prepareStatement("SELECT pg_catalog.set_config(?,?,?)");
		ps.setString(1, settingName);
		ps.setString(2, newValue);
		ps.setBoolean(3, isLocal);
		return resultStream(ps, ps.execute());
	}

	/**
	 * Return the {@link #resultStream resultStream} from
	 * {@code CREATE EXTENSION pljava}.
	 */
	public static Stream<Object> createExtensionPLJava(Connection c)
	throws Exception
	{
		Statement s = c.createStatement();
		return resultStream(s, s.execute("CREATE EXTENSION pljava"));
	}

	/**
	 * Install a jar.
	 * @return a {@link #resultStream resultStream} from executing the statement
	 */
	public static Stream<Object> installJar(
		Connection c, String uri, String jarName, boolean deploy)
	throws Exception
	{
		PreparedStatement ps =
			c.prepareStatement("SELECT sqlj.install_jar(?,?,?)");
		ps.setString(1, uri);
		ps.setString(2, jarName);
		ps.setBoolean(3, deploy);
		return resultStream(ps, ps.execute());
	}

	/**
	 * Remove a jar.
	 * @return a {@link #resultStream resultStream} from executing the statement
	 */
	public static Stream<Object> removeJar(
		Connection c, String jarName, boolean undeploy)
	throws Exception
	{
		PreparedStatement ps =
			c.prepareStatement("SELECT sqlj.remove_jar(?,?)");
		ps.setString(1, jarName);
		ps.setBoolean(2, undeploy);
		return resultStream(ps, ps.execute());
	}

	/**
	 * Set the class path for a schema.
	 * @return a {@link #resultStream resultStream} from executing the statement
	 */
	public static Stream<Object> setClasspath(
		Connection c, String schema, String... jarNames)
	throws Exception
	{
		PreparedStatement ps =
			c.prepareStatement("SELECT sqlj.set_classpath(?,?)");
		ps.setString(1, schema);
		ps.setString(2, String.join(":", jarNames));
		return resultStream(ps, ps.execute());
	}

	/**
	 * Execute some arbitrary SQL
	 * @return a {@link #resultStream resultStream} from executing the statement
	 */
	public static Stream<Object> q(Connection c, String sql) throws Exception
	{
		Statement s = c.createStatement();
		return resultStream(s, s.execute(sql));
	}

	/**
	 * Execute some arbitrary SQL and pass
	 * the {@link #resultStream resultStream}
	 * to {@link #print(Stream<Object>) print}.
	 */
	public static void qp(Connection c, String sql) throws Exception
	{
		print(q(c, sql));
	}

	/**
	 * Print the result of some query or operation already in the form of
	 * a {@link #resultStream resultStream}.
	 *<p>
	 * This is nothing but a two-keystroke alias for
	 * {@link #print(Stream<Object>) print}.
	 */
	public static void qp(Stream<Object> s) throws Exception
	{
		print(s);
	}

	/**
	 * Return true if the examples jar includes the
	 * {@code org.postgresql.pljava.example.saxon.S9} class (meaning the
	 * appropriate Saxon jar must be installed and on the classpath first before
	 * the examples jar can be deployed, unless {@code check_function_bodies}
	 * is {@code off} to skip dependency checking.
	 */
	public static boolean examplesNeedSaxon() throws Exception
	{
		dryExtract();
		try ( JarFile jf = new JarFile(s_examplesJar) )
		{
			return jf.stream().anyMatch(e ->
				"org/postgresql/pljava/example/saxon/S9.class"
				.equals(e.getName()));
		}
	}

	/**
	 * Install the examples jar, under the name {@code examples}.
	 *<p>
	 * The jar is specified by a {@code file:} URI and the path is the one where
	 * this installer installed (or would have installed) it.
	 * @return a {@link #resultStream resultStream} from executing the statement
	 */
	public static Stream<Object> installExamples(Connection c, boolean deploy)
	throws Exception
	{
		dryExtract();
		return installJar(c, "file:"+s_examplesJar, "examples", deploy);
	}

	/**
	 * Produce a {@code Stream} of the (in JDBC, possibly multiple) results
	 * from an {@code execute} method on a {@code Statement}.
	 *<p>
	 * Each result in the stream will be an instance of one of:
	 * {@code ResultSet}, {@code Long} (an update count, positive or zero),
	 * {@code SQLWarning}, or some other {@code SQLException}. A warning or
	 * exception may have others chained to it, which its own {@code iterator}
	 * or {@code forEach} methods should be used to traverse; or, use
	 * {@code flatMap(}{@link #flattenDiagnostics Node::flattenDiagnostics}) to
	 * obtain a stream presenting each diagnostic in a chain in turn.
	 *<p>
	 * Exists mainly to encapsulate the rather fiddly logic of extracting that
	 * sequence of results using the {@code Statement} API.
	 * @param s the Statement from which to extract results
	 * @param isResultSet the boolean return value of the execute method whose
	 * results are to be extracted, indicating whether the first result is
	 * a {@code ResultSet}.
	 * @return a Stream as described above.
	 */
	public static Stream<Object> resultStream(
		final Statement s, boolean isResultSet)
	{
		final Object[] nextHolder = new Object [ 1 ];
		Object seed;

		final Supplier<Object> resultSet = () ->
		{
			try
			{
				return s.getResultSet();
			}
			catch ( SQLException e )
			{
				return e;
			}
		};

		final Supplier<Object> updateCount = () ->
		{
			try
			{
				long count = s.getLargeUpdateCount();
				return ( -1 == count ) ? null : count;
			}
			catch ( SQLException e )
			{
				return e;
			}
		};

		final Supplier<Object> warnings = () ->
		{
			try
			{
				SQLWarning w = s.getWarnings();
				if ( null != w )
				{
					try
					{
						s.clearWarnings();
					}
					catch ( SQLException e )
					{
						nextHolder [ 0 ] = e;
					}
				}
				return w;
			}
			catch ( SQLException e )
			{
				return e;
			}
		};

		/*
		 * First get warnings, if any.
		 * There is a remote chance this can return an exception rather than a
		 * warning, an even more remote chance it returns a warning and leaves
		 * an exception in nextHolder.
		 * Only if it did neither is there any point in proceeding to get an
		 * update count or result set.
		 * If we do, and there was a warning, we use the warning as the seed and
		 * save the first update count or result set in nextHolder.
		 */
		seed = warnings.get();
		if ( (null == seed || seed instanceof SQLWarning)
			&& null == nextHolder [ 0 ] )
		{
			Object t;
			if ( isResultSet )
				t = resultSet.get();
			else
				t = updateCount.get();
			if ( null == seed )
				seed = t;
			else
				nextHolder [ 0 ] = t;
		}

		UnaryOperator<Object> next = o ->
		{
			if ( o instanceof SQLException && !(o instanceof SQLWarning) )
				return null;

			o = nextHolder [ 0 ];
			if ( null != o )
			{
				nextHolder [ 0 ] = null;
				return o;
			}

			o = warnings.get();
			if ( null != o )
				return o;

			try
			{
				if ( s.getMoreResults() )
					return resultSet.get();
				else
					return updateCount.get();
			}
			catch ( SQLException e )
			{
				return e;
			}
		};

		return Stream.iterate(seed, Objects::nonNull, next)
			.onClose(() ->
				{
					try
					{
						s.close();
					}
					catch ( SQLException e )
					{
					}
				}
			);
	}

	/**
	 * A flat-mapping function to expand any {@code SQLException} or
	 * {@code SQLWarning} instance in a result stream into the stream of
	 * possibly multiple linked diagnostics and causes in the encounter order
	 * of the {@code SQLException} iterator.
	 *<p>
	 * Any other object is returned in a singleton stream.
	 */
	public static Stream<Object> flattenDiagnostics(Object oneResult)
	{
		if ( oneResult instanceof SQLException )
		{
			Spliterator<Object> s = spliteratorUnknownSize(
				((SQLException)oneResult).iterator(),
				IMMUTABLE | NONNULL | ORDERED);
			return stream(s, false);
		}
		return Stream.of(oneResult);
	}

	/**
	 * Print streamed results of a {@code Statement} in
	 * (somewhat) readable fashion.
	 *<p>
	 * Uses {@code writeXml} of {@code WebRowSet}, which is very verbose, but
	 * about the easiest way to readably dump a {@code ResultSet} in just a
	 * couple lines of code.
	 */
	public static void print(Stream<Object> s) throws Exception
	{
		WebRowSet wrs = RowSetProvider.newFactory().createWebRowSet();
		try ( Stream<Object> flat = s.flatMap(Node::flattenDiagnostics) )
		{
			for ( Object o : (Iterable<Object>)flat::iterator )
			{
				if ( o instanceof ResultSet )
				{
					try (ResultSet rs = (ResultSet)o)
					{
						wrs.populate(rs);
						wrs.writeXml(System.out);
					}
					finally
					{
						wrs.release();
					}
				}
				else if ( o instanceof Long )
					System.out.println("<?updateCount " + o + " ?>");
				else if ( o instanceof Throwable )
					print((Throwable)o);
				else
					System.out.println("<!-- unexpected "
						+ o.getClass().getName()
						+ " from resultStream() -->");
			}
		}
	}

	/**
	 * Print a {@code Throwable} retrieved from a {@code resultStream}, with
	 * special handling for {@code SQLException} and {@code SQLWarning}.
	 *<p>
	 * In keeping with the XMLish vibe established by
	 * {@link #print(Stream<Object>) print} for other items in a result
	 * stream, this will render a {@code Throwable} as an {@code error},
	 * {@code warning}, or {@code info} element (PostgreSQL's finer
	 * distinctions of severity are not exposed by pgjdbc-ng's API.)
	 *<p>
	 * An element will have a {@code message} attribute if it has a message.
	 * It will have a {@code code} attribute containing the SQLState, if it is
	 * an instance of {@code SQLException}, unless it is rendered as an
	 * {@code info} element and the state is {@code 00000}. An instance of
	 * {@code SQLWarning} will be rendered as a {@code warning} unless its class
	 * (two leftmost code positions) is {@code 00}, in which case it will be
	 * {@code info}. Anything else is an {@code error}.
	 */
	public static void print(Throwable t)
	{
		String msg = t.getMessage();
		String sqlState = null;
		String element = "error";
		if ( t instanceof SQLException )
		{
			sqlState = ((SQLException)t).getSQLState();
			if ( t instanceof SQLWarning )
			{
				if ( sqlState.startsWith("00") )
				{
					element = "info";
					if ( "00000".equals(sqlState) )
						sqlState = null;
				}
				else
					element = "warning";
			}
		}
		StringBuilder b = new StringBuilder("<" + element);
		if ( null != sqlState )
			b.append(" code=").append(asAttribute(sqlState));
		if ( null != msg )
			b.append(" message=").append(asAttribute(msg));
		System.out.println(b.append("/>"));
	}

	/**
	 * Escape a string as an XML attribute.
	 *<p>
	 * Right on the borderline of trivial enough to implement here rather than
	 * forcing the beleaguered user to add yet one more --add-modules for
	 * {@code java.xml} just to run this in {@code jshell}.
	 */
	private static String asAttribute(String s)
	{
		int[] aposquot = new int[2];
		s.codePoints().forEach(c ->
		{
			if ( '\'' == c )
				++ aposquot[0];
			else if ( '"' == c )
				++ aposquot[1];
		});
		char delim = aposquot[0] > aposquot[1] ? '"' : '\'';
		Matcher m = compile('"' == delim ? "[<&\"]" : "[<&']").matcher(s);
		s = m.replaceAll(r ->
		{
			switch (r.group())
			{
			case "<": return "&lt;";
			case "&": return "&amp;";
			case "'": return "&apos;";
			case "\"": return "&quot;";
			}
			throw new AssertionError();
		});
		return delim + s + delim;
	}

	/*
	 * For parsing the postmaster.pid file, these have been the lines at least
	 * back to 9.1, except PM_STATUS appeared in 10. That's too bad; before 10
	 * it isn't possible to wait for a status of ready, which may necessitate
	 * just retrying the initial connection if the timing is unlucky.
	 * Cribbed from <utils/pidfile.h>.
	 */
	private static final int LOCK_FILE_LINE_PID         = 1;
	private static final int LOCK_FILE_LINE_DATA_DIR 	= 2;
	private static final int LOCK_FILE_LINE_START_TIME	= 3;
	private static final int LOCK_FILE_LINE_PORT 		= 4;
	private static final int LOCK_FILE_LINE_SOCKET_DIR	= 5;
	private static final int LOCK_FILE_LINE_LISTEN_ADDR = 6;
	private static final int LOCK_FILE_LINE_SHMEM_KEY	= 7;
	private static final int LOCK_FILE_LINE_PM_STATUS	= 8;
	private static final String PM_STATUS_READY = "ready   ";

	/**
	 * Wait for the {@code postmaster.pid} file to have the right contents
	 * (the right pid for process <em>p</em>, and ready status for PG 10+).
	 *<p>
	 * The {code PostgresNode.pm} version of this is also used when shutting
	 * down, and waits for the file to go away; that could be implemented here,
	 * but not today.
	 */
	private void wait_for_pid_file(Process p) throws Exception
	{
		Path datadir = data_dir();
		Path pidfile = datadir.resolve("postmaster.pid");
		Path pidonly = pidfile.getFileName();

		/*
		 * Initialize a watch service just in case the postmaster.pid file
		 * isn't there or has the wrong contents when we first look,
		 * and we need to wait for something to happen to it.
		 */
		try (WatchService watcher = datadir.getFileSystem().newWatchService())
		{
			WatchKey key =
				datadir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);

			for ( ;; )
			{
				try
				{
					String[] status = lines(pidfile).toArray(String[]::new);
					if ( (status.length == LOCK_FILE_LINE_PM_STATUS)
						&& (Long.parseLong(status[LOCK_FILE_LINE_PID - 1])
							== p.pid())
						&& PM_STATUS_READY.equals(
							status[LOCK_FILE_LINE_PM_STATUS - 1]) )
						return;
					if ( (status.length == LOCK_FILE_LINE_SHMEM_KEY)
						&& (Long.parseLong(status[LOCK_FILE_LINE_PID - 1])
							== p.pid())
						&& waitPrePG10() )
						return;
				}
				catch (NoSuchFileException e)
				{
				}

				/*
				 * The file isn't there yet, or isn't fully written or "ready"
				 */
				for ( ;; )
				{
					if ( ! p.isAlive() )
						throw new IllegalStateException(
							"Server process exited while awaiting \"ready\" " +
							"with status " + p.exitValue());
					WatchKey k = watcher.poll(250, MILLISECONDS);
					if ( interrupted() )
						throw new InterruptedException();
					if ( null == k )
						break; // timed out; check again just in case
					assert key.equals(k); // it's the only one we registered
					boolean recheck = k.pollEvents().stream()
						.anyMatch(e ->
							{
								WatchEvent.Kind<?> kind = e.kind();
								if ( OVERFLOW == kind )
									return true;
								if ( ENTRY_CREATE == kind &&
									pidonly.equals(
										ENTRY_CREATE.type().cast(e.context())) )
									return true;
								if ( ENTRY_MODIFY == kind &&
									pidonly.equals(
										ENTRY_MODIFY.type().cast(e.context())) )
									return true;
								return false;
							}
						);
					if ( recheck )
						break;
					k.reset();
				}
			}
		}
	}

	/**
	 * Check whether the server being started is earlier than PG 10 and, if so,
	 * sleep for a period expected to be adequate for it to become ready to
	 * accept connections, then return true.
	 *<p>
	 * This is called from the generic {@code wait_for_pid_file}, only if the
	 * file has already appeared and has all entries but {@code PM_STATUS}. That
	 * could mean it is a pre-PG10 server that will not write {@code PM_STATUS},
	 * or a PG 10 or later server that was caught in mid-write to the file.
	 *<p>
	 * Return false if it is PG 10 or later, in which case the caller should
	 * continue waiting for {@code PM_STATUS_READY} to appear.
	 *<p>
	 * The fixed wait in the pre-PG10 case should not need to be terribly long,
	 * because this method isn't called until the PID file has already appeared,
	 * so that much of server startup has already occurred.
	 */
	private boolean waitPrePG10() throws Exception
	{
		if ( lines(data_dir().resolve("PG_VERSION")).limit(1).noneMatch(
			s -> s.contains(".")) )
			return false;
		Thread.sleep(2000); // and hope
		return true;
	}
}
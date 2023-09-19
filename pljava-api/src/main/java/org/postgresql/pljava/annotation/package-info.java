/*
 * Copyright (c) 2015-2023 Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Tada AB
 *   Purdue University
 */
/**
 * Annotations for use in Java code to generate the SQLJ Deployment Descriptor
 * automatically.
 * <p>
 * To define functions or types in PL/Java requires more than one step. The
 * Java code must be written, compiled to a jar, and made available to the
 * PostgreSQL server. Before the server can use the objects in the jar, the
 * corresponding PostgreSQL declarations of functions/types/triggers/operators,
 * and so on, must be made in SQL. This often lengthy SQL script (and the
 * version that undoes it when uninstalling the jar) can be written in a
 * prescribed form and stored inside the jar itself as an "SQLJ Deployment
 * Descriptor", and processed automatically when the jar is installed in or
 * removed from the backend.
 * <p>
 * To write the deployment descriptor by hand can be tedious and error-prone,
 * as it must largely duplicate the method and type declarations in the
 * Java code, but using SQL's syntax and types in place of Java's. Instead,
 * when the annotations in this package are used in the Java code, the Java
 * compiler itself will generate a deployment descriptor file, ready to include
 * with the compiled classes to make a complete SQLJ jar.
 * <p>
 * Automatic descriptor generation requires attention to a few things.
 * <ul>
 * <li>The {@code pljava-api} jar must be on the Java compiler's class path.
 * (All but the simplest PL/Java functions probably refer to some class in
 * PL/Java's API anyway, in which case the jar would already have to be on
 * the class path.)
 * <li>When recompiling after changing only a few sources, it is possible the
 * Java compiler will only process a subset of the source files containing
 * annotations. If so, it may generate an incomplete deployment descriptor,
 * and a clean build may be required to ensure the complete descriptor is
 * written.
 * <li>Additional options are available when invoking the Java compiler, and
 * can be specified with <code>-Aoption=value</code> on the command line:
 * <dl>
 * <dt><code>ddr.output</code>
 * <dd>The file name to be used for the generated deployment descriptor.
 * If not specified, the file will be named <code>pljava.ddr</code> and found
 * in the top directory of the tree where the compiled class files are written.
 * <dt><code>ddr.name.trusted</code>
 * <dd>The language name that will be used to declare methods that are
 * annotated to have {@link org.postgresql.pljava.annotation.Function.Trust#SANDBOXED} behavior. If not
 * specified, the name <code>java</code> will be used. It must match the name
 * used for the "trusted" language declaration when PL/Java was installed.
 * <dt><code>ddr.name.untrusted</code>
 * <dd>The language name that will be used to declare methods that are
 * annotated to have {@link org.postgresql.pljava.annotation.Function.Trust#UNSANDBOXED} behavior. If not
 * specified, the name <code>javaU</code> will be used. It must match the name
 * used for the "untrusted" language declaration when PL/Java was installed.
 * <dt><code>ddr.implementor</code>
 * <dd>The identifier (defaulting to {@code PostgreSQL} if not specified here)
 * that will be used in the {@code <implementor block>}s wrapping any SQL
 * generated from elements that do not specify their own. If this is set to a
 * single hyphen (-), elements that specify no implementor will produce plain
 * {@code <SQL statement>}s not wrapped in {@code <implementor block>}s.
 * <dt><code>ddr.reproducible</code>
 * <dd>When {@code true} (the default), SQL statements are written to the
 * deployment descriptor in an order meant to be consistent across successive
 * compilations of the same sources. This option is further discussed below.
 * </dl>
 * <li>The deployment descriptor may contain statements that cannot succeed if
 * placed in the wrong order, and to keep a manually-edited script in a workable
 * order while adding and modifying code can be difficult. Most of the
 * annotations in this package accept arbitrary {@code requires} and
 * {@code provides} strings, which can be used to control the order of
 * statements in the generated descriptor. The strings given for
 * {@code requires} and {@code provides} have no meaning to the
 * compiler, except that it will make sure not to write anything that
 * {@code requires} some string <em>X</em> into the generated script
 * before whatever {@code provides} it.
 * <li>There can be multiple ways to order the statements in the deployment
 * descriptor to satisfy the given {@code provides} and {@code requires}
 * relationships. While the compiler will always write the descriptor in an
 * order that satisfies those relationships, when the {@code ddr.reproducible}
 * option is {@code false}, the precise order may differ between successive
 * compilations of the same sources, which <em>should</em> not affect successful
 * loading and unloading of the jar with {@code install_jar} and
 * {@code remove_jar}. In testing, this can help to confirm that all of the
 * needed {@code provides} and {@code requires} relationships have been
 * declared. When the {@code ddr.reproducible} option is {@code true}, the order
 * of statements in the deployment descriptor will be one of the possible
 * orders, chosen arbitrarily but consistently between multiple compilations as
 * long as the sources are unchanged. This can be helpful in software
 * distribution when reproducible output is wanted.
 * </ul>
 */
package org.postgresql.pljava.annotation;

// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.starlark.java.cmd;

import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FileOptions;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.SyntaxError;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Main is a standalone interpreter for the core Starlark language. It does not yet support load
 * statements.
 *
 * <p>The sad class name is due to the linting tool, which forbids lowercase "starlark", and Java's
 * lack of renaming imports, which makes the name "Starlark" impractical due to conflicts with
 * lib.syntax.Starlark.
 */
class Main {
  private static final String START_PROMPT = ">> ";
  private static final String CONTINUATION_PROMPT = ".. ";

  private static final Charset CHARSET = StandardCharsets.ISO_8859_1;
  private final BufferedReader reader =
      new BufferedReader(new InputStreamReader(System.in, CHARSET));
  private final StarlarkThread thread;
  private final Module module = Module.create();

  // TODO(adonovan): set load-binds-globally option when we support load,
  // so that loads bound in one REPL chunk are visible in the next.
  private final FileOptions options = FileOptions.DEFAULT;

  {
    Mutability mu = Mutability.create("interpreter");
    thread = new StarlarkThread(mu, StarlarkSemantics.DEFAULT);
    thread.setPrintHandler((th, msg) -> System.out.println(msg));
  }

  private String prompt() {
    StringBuilder input = new StringBuilder();
    System.out.print(START_PROMPT);
    try {
      String lineSeparator = "";
      while (true) {
        String line = reader.readLine();
        if (line == null) {
          return null;
        }
        if (line.isEmpty()) {
          return input.toString();
        }
        input.append(lineSeparator).append(line);
        lineSeparator = "\n";
        System.out.print(CONTINUATION_PROMPT);
      }
    } catch (IOException e) {
      System.err.format("Error reading line: %s\n", e);
      return null;
    }
  }

  /** Provide a REPL evaluating Starlark code. */
  @SuppressWarnings("CatchAndPrintStackTrace")
  private void readEvalPrintLoop() {
    System.err.println("Welcome to Starlark (java.starlark.net)");
    String line;

    // TODO(adonovan): parse a compound statement, like the Python and
    // go.starlark.net REPLs. This requires a new grammar production, and
    // integration with the lexer so that it consumes new
    // lines only until the parse is complete.

    while ((line = prompt()) != null) {
      ParserInput input = ParserInput.fromString(line, "<stdin>");
      try {
        Object result = Starlark.execFile(input, options, module, thread);
        if (result != Starlark.NONE) {
          System.out.println(Starlark.repr(result));
        }
      } catch (SyntaxError.Exception ex) {
        for (SyntaxError error : ex.errors()) {
          System.err.println(error);
        }
      } catch (EvalException ex) {
        // TODO(adonovan): provide a SourceReader. Requires that we buffer the
        // entire history so that line numbers don't reset in each chunk.
        System.err.println(ex.getMessageWithStack());
      } catch (InterruptedException ex) {
        System.err.println("Interrupted");
      }
    }
  }

  /** Execute a Starlark file. */
  private int executeFile(String filename) {
    String content;
    try {
      content = new String(Files.readAllBytes(Paths.get(filename)), CHARSET);
      return execute(filename, content);
    } catch (IOException e) {
      // This results in such lame error messages as:
      // "Error reading a.star: java.nio.file.NoSuchFileException: a.star"
      System.err.format("Error reading %s: %s\n", filename, e);
      return 1;
    }
  }

  /** Execute a Starlark file. */
  private int execute(String filename, String content) {
    try {
      Starlark.execFile(ParserInput.fromString(content, filename), options, module, thread);
      return 0;
    } catch (SyntaxError.Exception ex) {
      for (SyntaxError error : ex.errors()) {
        System.err.println(error);
      }
      return 1;
    } catch (EvalException ex) {
      System.err.println(ex.getMessageWithStack());
      return 1;
    } catch (InterruptedException e) {
      System.err.println("Interrupted");
      return 1;
    }
  }

  public static void main(String[] args) throws IOException {
    String file = null;
    String cmd = null;
    String cpuprofile = null;

    // parse flags
    int i;
    for (i = 0; i < args.length; i++) {
      if (!args[i].startsWith("-")) {
        break;
      }
      if (args[i].equals("--")) {
        i++;
        break;
      }
      if (args[i].equals("-c")) {
        if (i + 1 == args.length) {
          throw new IOException("-c <cmd> flag needs an argument");
        }
        cmd = args[++i];
      } else if (args[i].equals("-cpuprofile")) {
        if (i + 1 == args.length) {
          throw new IOException("-cpuprofile <file> flag needs an argument");
        }
        cpuprofile = args[++i];
      } else {
        throw new IOException("unknown flag: " + args[i]);
      }
    }
    // positional arguments
    if (i < args.length) {
      if (i + 1 < args.length) {
        throw new IOException("too many positional arguments");
      }
      file = args[i];
    }

    if (cpuprofile != null) {
      FileOutputStream out = new FileOutputStream(cpuprofile);
      Starlark.startCpuProfile(out, Duration.ofMillis(10));
    }

    int exit;
    if (file == null) {
      if (cmd != null) {
        exit = new Main().execute("<command-line>", cmd);
      } else {
        new Main().readEvalPrintLoop();
        exit = 0;
      }
    } else if (cmd == null) {
      exit = new Main().executeFile(file);
    } else {
      System.err.println("usage: Starlark [-cpuprofile file] [-c cmd | file]");
      exit = 1;
    }

    if (cpuprofile != null) {
      Starlark.stopCpuProfile();
    }

    System.exit(exit);
  }
}

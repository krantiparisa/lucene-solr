package org.apache.lucene.expressions.js;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.DADD;
import static org.objectweb.asm.Opcodes.DCMPG;
import static org.objectweb.asm.Opcodes.DCMPL;
import static org.objectweb.asm.Opcodes.DDIV;
import static org.objectweb.asm.Opcodes.DNEG;
import static org.objectweb.asm.Opcodes.DREM;
import static org.objectweb.asm.Opcodes.DSUB;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFGE;
import static org.objectweb.asm.Opcodes.IFGT;
import static org.objectweb.asm.Opcodes.IFLE;
import static org.objectweb.asm.Opcodes.IFLT;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.LAND;
import static org.objectweb.asm.Opcodes.LOR;
import static org.objectweb.asm.Opcodes.LSHL;
import static org.objectweb.asm.Opcodes.LSHR;
import static org.objectweb.asm.Opcodes.LUSHR;
import static org.objectweb.asm.Opcodes.LXOR;
import static org.objectweb.asm.Opcodes.V1_7;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CharStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.Tree;
import org.apache.lucene.expressions.Expression;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.util.IOUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * An expression compiler for javascript expressions.
 * <p>
 * Example:
 * <pre class="prettyprint">
 *   Expression foo = JavascriptCompiler.compile("((0.3*popularity)/10.0)+(0.7*score)");
 * </pre>
 * <p>
 * See the {@link org.apache.lucene.expressions.js package documentation} for 
 * the supported syntax and functions.
 * 
 * @lucene.experimental
 */
public class JavascriptCompiler {

  static class Loader extends ClassLoader {
    Loader(ClassLoader parent) {
      super(parent);
    }

    public Class<? extends Expression> define(String className, byte[] bytecode) {
      return super.defineClass(className, bytecode, 0, bytecode.length).asSubclass(Expression.class);
    }
  }
  
  private static final int CLASSFILE_VERSION = V1_7;
  
  // We use the same class name for all generated classes as they all have their own class loader.
  // The source code is displayed as "source file name" in stack trace.
  private static final String COMPILED_EXPRESSION_CLASS = JavascriptCompiler.class.getName() + "$CompiledExpression";
  private static final String COMPILED_EXPRESSION_INTERNAL = COMPILED_EXPRESSION_CLASS.replace('.', '/');
  
  private static final Type EXPRESSION_TYPE = Type.getType(Expression.class);
  private static final Type FUNCTION_VALUES_TYPE = Type.getType(FunctionValues.class);

  private static final org.objectweb.asm.commons.Method
    EXPRESSION_CTOR = getMethod("void <init>(String, String[])"),
    EVALUATE_METHOD = getMethod("double evaluate(int, " + FunctionValues.class.getName() + "[])"),
    DOUBLE_VAL_METHOD = getMethod("double doubleVal(int)");
  
  // to work around import clash:
  private static org.objectweb.asm.commons.Method getMethod(String method) {
    return org.objectweb.asm.commons.Method.getMethod(method);
  }
  
  // This maximum length is theoretically 65535 bytes, but as its CESU-8 encoded we dont know how large it is in bytes, so be safe
  // rcmuir: "If your ranking function is that large you need to check yourself into a mental institution!"
  private static final int MAX_SOURCE_LENGTH = 16384;
  
  private final String sourceText;
  private final Map<String, Integer> externalsMap = new LinkedHashMap<String, Integer>();
  private final ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
  private GeneratorAdapter gen;
  
  private final Map<String,Method> functions;
  
  /**
   * Compiles the given expression.
   *
   * @param sourceText The expression to compile
   * @return A new compiled expression
   * @throws ParseException on failure to compile
   */
  public static Expression compile(String sourceText) throws ParseException {
    return new JavascriptCompiler(sourceText).compileExpression(JavascriptCompiler.class.getClassLoader());
  }
  
  /**
   * Compiles the given expression with the supplied custom functions.
   * <p>
   * Functions must return {@code double} and can take from zero to 256 {@code double} parameters.
   *
   * @param sourceText The expression to compile
   * @param functions map of String names to functions
   * @param parent a {@code ClassLoader} that should be used as the parent of the loaded class.
   *   It must contain all classes referred to by the given {@code functions}.
   * @return A new compiled expression
   * @throws ParseException on failure to compile
   */
  public static Expression compile(String sourceText, Map<String,Method> functions, ClassLoader parent) throws ParseException {
    if (parent == null) {
      throw new NullPointerException("A parent ClassLoader must be given.");
    }
    for (Method m : functions.values()) {
      checkFunction(m, parent);
    }
    return new JavascriptCompiler(sourceText, functions).compileExpression(parent);
  }
  
  /**
   * This method is unused, it is just here to make sure that the function signatures don't change.
   * If this method fails to compile, you also have to change the byte code generator to correctly
   * use the FunctionValues class.
   */
  @SuppressWarnings({"unused", "null"})
  private static void unusedTestCompile() {
    FunctionValues f = null;
    double ret = f.doubleVal(2);
  }
  
  /**
   * Constructs a compiler for expressions.
   * @param sourceText The expression to compile
   */
  private JavascriptCompiler(String sourceText) {
    this(sourceText, DEFAULT_FUNCTIONS);
  }
  
  /**
   * Constructs a compiler for expressions with specific set of functions
   * @param sourceText The expression to compile
   */
  private JavascriptCompiler(String sourceText, Map<String,Method> functions) {
    if (sourceText == null) {
      throw new NullPointerException();
    }
    this.sourceText = sourceText;
    this.functions = functions;
  }
  
  /**
   * Compiles the given expression with the specified parent classloader
   *
   * @return A new compiled expression
   * @throws ParseException on failure to compile
   */
  private Expression compileExpression(ClassLoader parent) throws ParseException {
    try {
      Tree antlrTree = getAntlrComputedExpressionTree();
      
      beginCompile();
      recursiveCompile(antlrTree, Type.DOUBLE_TYPE);
      endCompile();
      
      Class<? extends Expression> evaluatorClass = new Loader(parent)
        .define(COMPILED_EXPRESSION_CLASS, classWriter.toByteArray());
      Constructor<? extends Expression> constructor = evaluatorClass.getConstructor(String.class, String[].class);
      return constructor.newInstance(sourceText, externalsMap.keySet().toArray(new String[externalsMap.size()]));
    } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException exception) {
      throw new IllegalStateException("An internal error occurred attempting to compile the expression (" + sourceText + ").", exception);
    }
  }
  
  private void beginCompile() {
    classWriter.visit(CLASSFILE_VERSION, ACC_PUBLIC | ACC_SUPER | ACC_FINAL | ACC_SYNTHETIC, COMPILED_EXPRESSION_INTERNAL,
        null, EXPRESSION_TYPE.getInternalName(), null);
    String clippedSourceText = (sourceText.length() <= MAX_SOURCE_LENGTH) ? sourceText : (sourceText.substring(0, MAX_SOURCE_LENGTH - 3) + "...");
    classWriter.visitSource(clippedSourceText, null);
    
    GeneratorAdapter constructor = new GeneratorAdapter(ACC_PUBLIC | ACC_SYNTHETIC, EXPRESSION_CTOR, null, null, classWriter);
    constructor.loadThis();
    constructor.loadArgs();
    constructor.invokeConstructor(EXPRESSION_TYPE, EXPRESSION_CTOR);
    constructor.returnValue();
    constructor.endMethod();
    
    gen = new GeneratorAdapter(ACC_PUBLIC | ACC_SYNTHETIC, EVALUATE_METHOD, null, null, classWriter);
  }
  
  private void recursiveCompile(Tree current, Type expected) {
    int type = current.getType();
    String text = current.getText();
    
    switch (type) {
      case JavascriptParser.AT_CALL:
        Tree identifier = current.getChild(0);
        String call = identifier.getText();
        int arguments = current.getChildCount() - 1;
        
        Method method = functions.get(call);
        if (method == null) {
          throw new IllegalArgumentException("Unrecognized method call (" + call + ").");
        }
        
        int arity = method.getParameterTypes().length;
        if (arguments != arity) {
          throw new IllegalArgumentException("Expected (" + arity + ") arguments for method call (" +
              call + "), but found (" + arguments + ").");
        }
        
        for (int argument = 1; argument <= arguments; ++argument) {
          recursiveCompile(current.getChild(argument), Type.DOUBLE_TYPE);
        }
        
        gen.invokeStatic(Type.getType(method.getDeclaringClass()),
          org.objectweb.asm.commons.Method.getMethod(method));
        
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.ID:
        int index;
        
        if (externalsMap.containsKey(text)) {
          index = externalsMap.get(text);
        } else {
          index = externalsMap.size();
          externalsMap.put(text, index);
        }
        
        gen.loadArg(1);
        gen.push(index);
        gen.arrayLoad(FUNCTION_VALUES_TYPE);
        gen.loadArg(0);
        gen.invokeVirtual(FUNCTION_VALUES_TYPE, DOUBLE_VAL_METHOD);
        
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.HEX:
        pushLong(expected, Long.parseLong(text.substring(2), 16));
        break;
      case JavascriptParser.OCTAL:
        pushLong(expected, Long.parseLong(text.substring(1), 8));
        break;
      case JavascriptParser.DECIMAL:
        gen.push(Double.parseDouble(text));
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_NEGATE:
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        gen.visitInsn(DNEG);
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_ADD:
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DADD);
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_SUBTRACT:
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DSUB);
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_MULTIPLY:
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(Opcodes.DMUL);
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_DIVIDE:
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DDIV);
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_MODULO:
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DREM);
        gen.cast(Type.DOUBLE_TYPE, expected);
        break;
      case JavascriptParser.AT_BIT_SHL:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        recursiveCompile(current.getChild(1), Type.INT_TYPE);
        gen.visitInsn(LSHL);
        gen.cast(Type.LONG_TYPE, expected);
        break;
      case JavascriptParser.AT_BIT_SHR:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        recursiveCompile(current.getChild(1), Type.INT_TYPE);
        gen.visitInsn(LSHR);
        gen.cast(Type.LONG_TYPE, expected);
        break;
      case JavascriptParser.AT_BIT_SHU:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        recursiveCompile(current.getChild(1), Type.INT_TYPE);
        gen.visitInsn(LUSHR);
        gen.cast(Type.LONG_TYPE, expected);
        break;
      case JavascriptParser.AT_BIT_AND:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        recursiveCompile(current.getChild(1), Type.LONG_TYPE);
        gen.visitInsn(LAND);
        gen.cast(Type.LONG_TYPE, expected);
        break;
      case JavascriptParser.AT_BIT_OR:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        recursiveCompile(current.getChild(1), Type.LONG_TYPE);
        gen.visitInsn(LOR);
        gen.cast(Type.LONG_TYPE, expected);            
        break;
      case JavascriptParser.AT_BIT_XOR:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        recursiveCompile(current.getChild(1), Type.LONG_TYPE);
        gen.visitInsn(LXOR);
        gen.cast(Type.LONG_TYPE, expected);            
        break;
      case JavascriptParser.AT_BIT_NOT:
        recursiveCompile(current.getChild(0), Type.LONG_TYPE);
        gen.visitLdcInsn(new Long(-1));
        gen.visitInsn(LXOR);
        gen.cast(Type.LONG_TYPE, expected);
        break;
      case JavascriptParser.AT_COMP_EQ:
        Label labelEqTrue = new Label();
        Label labelEqReturn = new Label();
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DCMPL);
        
        gen.visitJumpInsn(IFEQ, labelEqTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelEqReturn);
        gen.visitLabel(labelEqTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelEqReturn);
        break;
      case JavascriptParser.AT_COMP_NEQ:
        Label labelNeqTrue = new Label();
        Label labelNeqReturn = new Label();
        
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DCMPL);
        
        gen.visitJumpInsn(IFNE, labelNeqTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelNeqReturn);
        gen.visitLabel(labelNeqTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelNeqReturn);
        break;
      case JavascriptParser.AT_COMP_LT:
        Label labelLtTrue = new Label();
        Label labelLtReturn = new Label();
        
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DCMPG);
        
        gen.visitJumpInsn(IFLT, labelLtTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelLtReturn);
        gen.visitLabel(labelLtTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelLtReturn);
        break;
      case JavascriptParser.AT_COMP_GT:
        Label labelGtTrue = new Label();
        Label labelGtReturn = new Label();
        
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DCMPL);
        
        gen.visitJumpInsn(IFGT, labelGtTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelGtReturn);
        gen.visitLabel(labelGtTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelGtReturn);
        break;
      case JavascriptParser.AT_COMP_LTE:
        Label labelLteTrue = new Label();
        Label labelLteReturn = new Label();
        
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DCMPG);
        
        gen.visitJumpInsn(IFLE, labelLteTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelLteReturn);
        gen.visitLabel(labelLteTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelLteReturn);
        break;
      case JavascriptParser.AT_COMP_GTE:
        Label labelGteTrue = new Label();
        Label labelGteReturn = new Label();
        
        recursiveCompile(current.getChild(0), Type.DOUBLE_TYPE);
        recursiveCompile(current.getChild(1), Type.DOUBLE_TYPE);
        gen.visitInsn(DCMPL);
        
        gen.visitJumpInsn(IFGE, labelGteTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelGteReturn);
        gen.visitLabel(labelGteTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelGteReturn);
        break;
      case JavascriptParser.AT_BOOL_NOT:
        Label labelNotTrue = new Label();
        Label labelNotReturn = new Label();
        
        recursiveCompile(current.getChild(0), Type.INT_TYPE);
        gen.visitJumpInsn(IFEQ, labelNotTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, labelNotReturn);
        gen.visitLabel(labelNotTrue);
        pushBoolean(expected, true);
        gen.visitLabel(labelNotReturn);
        break;
      case JavascriptParser.AT_BOOL_AND:
        Label andFalse = new Label();
        Label andEnd = new Label();
        
        recursiveCompile(current.getChild(0), Type.INT_TYPE);
        gen.visitJumpInsn(IFEQ, andFalse);
        recursiveCompile(current.getChild(1), Type.INT_TYPE);
        gen.visitJumpInsn(IFEQ, andFalse);
        pushBoolean(expected, true);
        gen.visitJumpInsn(GOTO, andEnd);
        gen.visitLabel(andFalse);
        pushBoolean(expected, false);
        gen.visitLabel(andEnd);
        break;
      case JavascriptParser.AT_BOOL_OR:
        Label orTrue = new Label();
        Label orEnd = new Label();
        
        recursiveCompile(current.getChild(0), Type.INT_TYPE);
        gen.visitJumpInsn(IFNE, orTrue);
        recursiveCompile(current.getChild(1), Type.INT_TYPE);
        gen.visitJumpInsn(IFNE, orTrue);
        pushBoolean(expected, false);
        gen.visitJumpInsn(GOTO, orEnd);
        gen.visitLabel(orTrue);
        pushBoolean(expected, true);
        gen.visitLabel(orEnd);
        break;
      case JavascriptParser.AT_COND_QUE:
        Label condFalse = new Label();
        Label condEnd = new Label();
        
        recursiveCompile(current.getChild(0), Type.INT_TYPE);
        gen.visitJumpInsn(IFEQ, condFalse);
        recursiveCompile(current.getChild(1), expected);
        gen.visitJumpInsn(GOTO, condEnd);
        gen.visitLabel(condFalse);
        recursiveCompile(current.getChild(2), expected);
        gen.visitLabel(condEnd);
        break;
      default:
        throw new IllegalStateException("Unknown operation specified: (" + current.getText() + ").");
    }
  }
  
  private void pushBoolean(Type expected, boolean truth) {
    switch (expected.getSort()) {
      case Type.INT:
        gen.push(truth);
        break;
      case Type.LONG:
        gen.push(truth ? 1L : 0L);
        break;
      case Type.DOUBLE:
        gen.push(truth ? 1. : 0.);
        break;
      default:
        throw new IllegalStateException("Invalid expected type: " + expected);
    }
  }
  
  private void pushLong(Type expected, long i) {
    switch (expected.getSort()) {
      case Type.INT:
        gen.push((int) i);
        break;
      case Type.LONG:
        gen.push(i);
        break;
      case Type.DOUBLE:
        gen.push((double) i);
        break;
      default:
        throw new IllegalStateException("Invalid expected type: " + expected);
    }
  }
  
  private void endCompile() {
    gen.returnValue();
    gen.endMethod();
    
    classWriter.visitEnd();
  }

  private Tree getAntlrComputedExpressionTree() throws ParseException {
    CharStream input = new ANTLRStringStream(sourceText);
    JavascriptLexer lexer = new JavascriptLexer(input);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    JavascriptParser parser = new JavascriptParser(tokens);

    try {
      return parser.expression().tree;

    } catch (RecognitionException exception) {
      throw new IllegalArgumentException(exception);
    } catch (RuntimeException exception) {
      if (exception.getCause() instanceof ParseException) {
        throw (ParseException)exception.getCause();
      }
      throw exception;
    }
  }
  
  /** 
   * The default set of functions available to expressions.
   * <p>
   * See the {@link org.apache.lucene.expressions.js package documentation}
   * for a list.
   */
  public static final Map<String,Method> DEFAULT_FUNCTIONS;
  static {
    Map<String,Method> map = new HashMap<String,Method>();
    try {
      final Properties props = new Properties();
      try (Reader in = IOUtils.getDecodingReader(JavascriptCompiler.class,
        JavascriptCompiler.class.getSimpleName() + ".properties", IOUtils.CHARSET_UTF_8)) {
        props.load(in);
      }
      for (final String call : props.stringPropertyNames()) {
        final String[] vals = props.getProperty(call).split(",");
        if (vals.length != 3) {
          throw new Error("Syntax error while reading Javascript functions from resource");
        }
        final Class<?> clazz = Class.forName(vals[0].trim());
        final String methodName = vals[1].trim();
        final int arity = Integer.parseInt(vals[2].trim());
        @SuppressWarnings({"rawtypes", "unchecked"}) Class[] args = new Class[arity];
        Arrays.fill(args, double.class);
        Method method = clazz.getMethod(methodName, args);
        checkFunction(method, JavascriptCompiler.class.getClassLoader());
        map.put(call, method);
      }
    } catch (NoSuchMethodException | ClassNotFoundException | IOException e) {
      throw new Error("Cannot resolve function", e);
    }
    DEFAULT_FUNCTIONS = Collections.unmodifiableMap(map);
  }
  
  private static void checkFunction(Method method, ClassLoader parent) {
    // We can only call the function if the given parent class loader of our compiled class has access to the method:
    final ClassLoader functionClassloader = method.getDeclaringClass().getClassLoader();
    if (functionClassloader != null) { // it is a system class iff null!
      boolean found = false;
      while (parent != null) {
        if (parent == functionClassloader) {
          found = true;
          break;
        }
        parent = parent.getParent();
      }
      if (!found) {
        throw new IllegalArgumentException(method + " is not declared by a class which is accessible by the given parent ClassLoader.");
      }
    }
    // do some checks if the signature is "compatible":
    if (!Modifier.isStatic(method.getModifiers())) {
      throw new IllegalArgumentException(method + " is not static.");
    }
    if (!Modifier.isPublic(method.getModifiers())) {
      throw new IllegalArgumentException(method + " is not public.");
    }
    if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
      throw new IllegalArgumentException(method.getDeclaringClass().getName() + " is not public.");
    }
    for (Class<?> clazz : method.getParameterTypes()) {
      if (!clazz.equals(double.class)) {
        throw new IllegalArgumentException(method + " must take only double parameters");
      }
    }
    if (method.getReturnType() != double.class) {
      throw new IllegalArgumentException(method + " does not return a double.");
    }
  }
}

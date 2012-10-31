package gov.nasa.jpl.ae.xml;

import japa.parser.ASTHelper;
import japa.parser.ASTParser;
import japa.parser.ParseException;
import japa.parser.ast.CompilationUnit;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.PackageDeclaration;
import japa.parser.ast.body.BodyDeclaration;
import japa.parser.ast.body.ClassOrInterfaceDeclaration;
import japa.parser.ast.body.ConstructorDeclaration;
import japa.parser.ast.body.FieldDeclaration;
import japa.parser.ast.body.MethodDeclaration;
import japa.parser.ast.body.ModifierSet;
import japa.parser.ast.body.TypeDeclaration;
import japa.parser.ast.body.VariableDeclarator;
import japa.parser.ast.body.VariableDeclaratorId;
import japa.parser.ast.expr.ArrayCreationExpr;
import japa.parser.ast.expr.AssignExpr;
import japa.parser.ast.expr.BinaryExpr;
import japa.parser.ast.expr.ClassExpr;
import japa.parser.ast.expr.ConditionalExpr;
import japa.parser.ast.expr.EnclosedExpr;
import japa.parser.ast.expr.Expression;
import japa.parser.ast.expr.FieldAccessExpr;
import japa.parser.ast.expr.MethodCallExpr;
import japa.parser.ast.expr.NameExpr;
import japa.parser.ast.expr.ObjectCreationExpr;
import japa.parser.ast.expr.ThisExpr;
import japa.parser.ast.expr.UnaryExpr;
import japa.parser.ast.stmt.BlockStmt;
import japa.parser.ast.stmt.ExplicitConstructorInvocationStmt;
import japa.parser.ast.stmt.Statement;
import japa.parser.ast.stmt.TryStmt;
import japa.parser.ast.type.ClassOrInterfaceType;
import japa.parser.ast.type.Type;
import japa.parser.ast.type.VoidType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.lang.Math;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import junit.framework.Assert;
// import javax.xml.xpath.XPathExpression;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import gov.nasa.jpl.ae.event.Dependency;
import gov.nasa.jpl.ae.event.DurativeEvent;
import gov.nasa.jpl.ae.event.FunctionCall;
import gov.nasa.jpl.ae.event.Parameter;
import gov.nasa.jpl.ae.event.Timepoint;
import gov.nasa.jpl.ae.event.Timepoint.Units;
import gov.nasa.jpl.ae.util.ClassUtils;
import gov.nasa.jpl.ae.util.CompareUtils;
import gov.nasa.jpl.ae.util.Debug;
import gov.nasa.jpl.ae.util.Pair;
import gov.nasa.jpl.ae.util.Timer;
import gov.nasa.jpl.ae.util.Utils;
import gov.nasa.jpl.ae.xml.EventXmlToJava.Param;

// Keep these for resolving class references. 
import gov.nasa.jpl.ae.event.*;
import gov.nasa.jpl.ae.solver.*;
import gov.nasa.jpl.ae.fuml.*;
import demandResponse.*;

/*
 * Translates XML to executable Java classes for Analysis Engine behavior (based
 * on events such as those of the Timeline Ontology).
 */
public class EventXmlToJava {
  
  // not using this yet
  public static final String charactersAssumedNotInIdentifiers = "<>+*/.";
 
  // A struct for packaging name, types, and values, which are used for many
  // purposes in the XML: parameters, args, dependencies, . . .
  public class Param {
    public String name;
    public String type;
    public String value;

    public Param( String name, String type, String value ) {
      this.name = name;
      this.type = type;
      this.value = value;
    }

    public Param( Node n ) {
      name = fixName( XmlUtils.getChildElementText( n, "name" ) );
      type = typeToClass( fixName( XmlUtils.getChildElementText( n, "type" ) ) );
      value = fixValue( XmlUtils.getChildElementText( n, "value" ) );
    }

    public String toString() {
      return "(" + name + ", " + type + ", " + value + ")";
    }
  }

  // Used for generating unique variable names where unspecified.
  protected static int counter = 0;

  // The source XML document.
  protected Document xmlDocDOM = null;

  // The top level XML DOM node
  Node scenarioNode = null;
  
  // The long name of the class currently being processed.
  protected String currentClass = null;

  // The javaparser.CompilationUnit for the class currently being processed.
  protected CompilationUnit currentCompilationUnit = null;
  
  // Map: simpleName -> javaparser.CompilationUnit
  protected Map< String, CompilationUnit > classes =
      new TreeMap< String, CompilationUnit >();

  // Map: longName -> parameter name -> Param
  protected Map< String, Map< String, Param > > paramTable =
      new TreeMap< String, Map< String, Param > >();

  // Map: longName -> method name -> set of javaparser.MethodDeclarations
  protected Map< String, Map< String, Set< MethodDeclaration > > > methodTable =
      new TreeMap< String, Map< String, Set< MethodDeclaration > > >();
  
//  // Map class name (long?) -> set of javaparser.ConstructorDeclarations
//  protected Map< String, Set< ConstructorDeclaration> > constructorDeclarations =
//      new TreeMap< String, Set< ConstructorDeclaration > >();
  
  // Map: inner class name -> enclosing class name
//  protected Map< String, Set< String > > innerToEnclosingClassNames =
//      new TreeMap< String, Set< String > >();
  // WARNING! TODO -- Two classes could nest classes with the same name, so
  // the Map<S,Set<S>> above is correct, but maybe not useful.  Instead, will
  // keep the current enclosing class, and the current scoped name.
  protected Map< String, String > nestedToEnclosingClassNames =
      new TreeMap< String, String >();
//  protected String currentEnclosingClassName;
//  protected String currentScopedClassName;
  protected Map< String, Boolean > isStaticMap = new TreeMap< String, Boolean >();
  protected Map< String, Boolean > isEventMap = new TreeMap< String, Boolean >();
  
  // The package for generated Java files. This is where Java files will be
  // written in {PROJECT}/src.
  protected String packageName = "generated";
  
  String xmlFileName = "exampleDRScenario.xml";
  
  // This is for handling class names outside Java syntax.
  protected NameTranslator nameTranslator = new NameTranslator();

  // These are for removing default dependencies that are overridden.
  private boolean gotStartTimeDependency;
  private boolean gotEndTimeDependency;
  private boolean gotDurationDependency;
  demandResponse.Customer c = new Customer( "stupid class loader" );
  ObjectFlow<Object> o = new ObjectFlow< Object >( "stupid class loader" );

  protected JavaCompiler compiler = null;
  protected StandardJavaFileManager fileManager = null;

  public JavaCompiler getCompiler() {
    if ( compiler == null ) {
      compiler = ToolProvider.getSystemJavaCompiler();
    }
    return compiler;
  }

  public StandardJavaFileManager getFileManager() {
    if ( fileManager == null ) {
      fileManager = ( getCompiler() == null ? null : getCompiler().getStandardFileManager(null, null, null) );
    }
    return fileManager;
  }

  protected ClassLoader loader = null;
  protected Class<?> mainClass = null;

  protected DurativeEvent mainInstance = null;

  public EventXmlToJava( String xmlFileName, String pkgName, boolean translate )
    throws ParserConfigurationException, SAXException, IOException {
    System.out.println( "\nEventXmlToJava(xmlFileName=" + xmlFileName
                        + ", packageName=" + pkgName + ", translate="
                        + translate + "): starting stats timer\n" );
    Timer timer = new Timer();
    
    this.xmlFileName = xmlFileName;
    if ( pkgName != null && !pkgName.equals( "" ) ) {
      this.packageName = pkgName;
    }
    init();
    if ( translate ) {
      translate();
    }
    System.out.println( "\nEventXmlToJava(xmlFileName=" + xmlFileName
                        + ", packageName=" + pkgName + ", translate="
                        + translate + "): finished\n" + timer + "\n" );
    
  }
  public EventXmlToJava( String xmlFileName, String pkgName )
      throws ParserConfigurationException, SAXException, IOException {
    this.xmlFileName = xmlFileName;
    if ( pkgName != null && !pkgName.equals( "" ) ) {
      this.packageName = pkgName;
    }
    init();
    translate();
  }
  public void init() throws ParserConfigurationException, SAXException, IOException {

    if ( Debug.isOn() ) Debug.outln( "random double to test repeatability = "
                 + Random.global.nextDouble() );
    
    System.out.println("xml file name = " + this.xmlFileName );
    System.out.println("package name = " + this.packageName );
    if ( Debug.isOn() ) Debug.outln("xml file name = " + this.xmlFileName );
    if ( Debug.isOn() ) Debug.outln("package name = " + this.packageName );

    // Translate XML to a DOM Document.
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware( true );
    DocumentBuilder builder;
    // XPathExpression expr = null;
    builder = factory.newDocumentBuilder();
    xmlDocDOM = builder.parse( xmlFileName );

    if ( !XmlUtils.validateXML( xmlFileName, xmlDocDOM ) ) {
      if ( Debug.isOn() ) Debug.outln( "Warning! XML file "
                          + xmlFileName
                          + " does not validate against its schema definition.  "
                          + "Continuing anyway." );
    }

    scenarioNode = XmlUtils.findNode( xmlDocDOM, "scenario" );
    Assert.assertNotNull( scenarioNode );
    
    // get units
    String timeUnits = XmlUtils.getChildElementText( scenarioNode, "timeUnits" );
    if ( timeUnits == null || timeUnits.isEmpty() ) {
      if ( Debug.isOn() ) Debug.outln( "no units specified; using default" );
    } else {
      Timepoint.setUnits( timeUnits );
    }
    System.out.println( "units = " + Timepoint.getUnits() );
    
    // get epoch
    String epochString = XmlUtils.getChildElementText( scenarioNode, "epoch" );
    if ( epochString == null || epochString.isEmpty() ) {
      if ( Debug.isOn() ) Debug.outln( "no epoch specified; using default" );
    } else {
      Timepoint.setEpoch( epochString );
    }
    System.out.println( "epoch = " + Timepoint.getEpoch() );

    // get horizon duration
    String durationString = XmlUtils.getChildElementText( scenarioNode, "horizon" );
    if ( durationString == null || durationString.isEmpty() ) {
      if ( Debug.isOn() ) Debug.errln( "no duration specified; using default" );
    } else {
      int secs = Math.max( 0, 1 );  // stupid class loader
      secs = XmlUtils.getDurationInSeconds( durationString ) ;
      
      Timepoint.setHorizonDuration( (int)(secs / Units.conversionFactor( Units.seconds )) );
    }
    System.out.println( "horizon duration = " + Timepoint.getHorizonDuration()
                        + Timepoint.getUnits() );
    
    // build tables
    buildParamTable( xmlDocDOM, paramTable );
    buildMethodTable( xmlDocDOM, methodTable );  
  }
  
  public void translate()
      throws ParserConfigurationException, SAXException, IOException {

    // pre-process classes
    processClassDeclarations( scenarioNode, null, "classes", false, true );
    // pre-process events
    processClassDeclarations( scenarioNode, null, "events", false, true );

    // Add constructors for invocations.
    addConstructors();
    
    // process classes first
    processClassDeclarations( scenarioNode, null, "classes", false, false );

    // process events
    processClassDeclarations( scenarioNode, null, "events", false, false );

    // process event to be executed
    NodeList nodeList = xmlDocDOM.getElementsByTagName( "eventToBeExecuted" );
    Assert.assertTrue( nodeList.getLength() < 2 );
    if ( nodeList.getLength() == 1 ) {
      Node node = nodeList.item( 0 );
      processExecutionEvent( node );
    }

  }

  /**
   * Fix all words in the string, name, so that it can be used as a Java
   * identifier or type name. The assumption is that a name may have the form
   * foo<bar>.barfoo, in which case each of the following are passed to
   * fixSimpleName(): "foo", "bar", and "barfoo". If the string begins with a
   * number, it does not translate it into a new name but returns it unchanged,
   * assuming that it is a number.
   * 
   * @param name
   *          The string to be fixed.
   * @return A translation of name into a valid Java identifier or type name.
   */
  protected String fixName( String name ) {
    if ( name == null ) return null;
    return name;
/*    StringBuffer sb = new StringBuffer();
    Pattern pattern = Pattern.compile( "\\b" ); // \b = word boundary
    Matcher matcher = pattern.matcher( name );
    int pos = 0;
    boolean nextIsWord = false;
    //String lastText = "";
    while ( matcher.find() ) {
      String text = name.substring( pos, matcher.start() );
      //if ( Debug.isOn() ) Debug.outln( "text = name.substring( " + pos + ", "
      //                    + matcher.start() + " ) = \"" + text + "\"" );
      pos = matcher.start();
//boolean textNotAllWhitespace = false;
      if ( nextIsWord && Utils.toDouble( text ) == null ) {
        text = fixSimpleName( text );
      }
//      if ( !nextIsWord && Utils.toDouble( lastText ) == null && textNotAllWhitespace  ) {
//        text = fixSimpleName( text );
//      }
      sb.append( text );
      nextIsWord = !nextIsWord;
//      lastText = text;
    }
    // Get any trailing non-words
    sb.append( name.substring( pos ) );
    if ( Debug.isOn() ) Debug.outln("fixName(\"" + name + "\") = \"" + sb.toString() + "\"" );
    return sb.toString();
*/  }
  
  /**
   * Fix the string identifier, name so that it can be used as a Java identifier
   * or type name.
   * 
   * @param name
   *          The string to be fixed.
   * @return A translation of name into a valid Java identifier or type name.
   */
  protected String fixSimpleName( String name ) {
    return name;
/*    if ( name == null ) return null;
    //String paramPart = Utils.parameterPartOfName( name );
    //String noParamName = Utils.noParameterName( name );
    //assert name.trim().equals( ( noParamName + paramPart ).trim() );
    String javaName = nameTranslator.translate( name, "xml", "java" );
    // TODO -- REVIEW -- Do we need to sweep though and get names first before values?
//    for ( Map.Entry e : classes.entrySet() ) {
//    }
    //if ( Debug.isOn() ) Debug.outln("fixSimpleName(\"" + name + "\") = \"" + javaName + "\"" );
    return javaName;
*/  }

  /**
   * Fix all names in the string so that they can be used as Java identifiers
   * or type names.  This currently just calls fixName( value ) since fixName()
   * does not change number tokens in the string.
   * 
   * @param value
   *          The string to be fixed.
   * @return A translation of name into a valid Java identifier or type name.
   */
  protected String fixValue( String value ) {
    return value;
/*    if ( value == null ) return null;
    //String javaValue = nameTranslator.substitute( value, "xml", "java" );
    String javaValue = fixName( value );
    //if ( Debug.isOn() ) Debug.outln("fixName(\"" + value + "\") = \"" + javaValue + "\"" );
    return javaValue;
*/  }
  
  // Get the name of the class from the DOM node. If it is an inner class,
  // prepend the names of the enclosing classes for proper scope (but leaving
  // off the package name).
  public String getClassName( Node classOrEventNode ) {
    String name = "";
    Node classNode = classOrEventNode;
    if ( classOrEventNode.getLocalName().equals( "event" ) ) {
      classNode = XmlUtils.getChildNode( classOrEventNode, "class" );
      if ( classNode == null ) classNode = classOrEventNode;  // warning?
    }
    while ( classNode != null ) {
      name =
          fixName( XmlUtils.getChildElementText( classNode, "name" ) ) + "."
                   + name;
      classNode =
          XmlUtils.getEnclosingNodeWithName( classNode.getParentNode(),
                                             "class" );
    }
    // remove '.' at end of string for first pass through loop above.
    name = name.substring( 0, name.length() - 1 );
    return name;
  }

  public String getEnclosingClassName( String className ) {
    if ( className == null ) return null;
    String enclosingClassName = nestedToEnclosingClassNames.get( className ); 
    if ( !Utils.isNullOrEmpty( enclosingClassName ) ) {
      if ( Debug.isOn() ) Debug.outln( "getEnclosingClassName(" + className + ") = "
                   + enclosingClassName );
      return enclosingClassName;
    }
    String scopedName = getClassNameWithScope( className );
    if ( !Utils.isNullOrEmpty( scopedName ) ) {
      enclosingClassName = nestedToEnclosingClassNames.get( scopedName ); 
//      if ( !Utils.isNullOrEmpty( enclosingClassName ) ) {
//        return true;
//      }
    }
    if ( Debug.isOn() ) Debug.outln( "getEnclosingClassName(" + className + ") = "
                 + enclosingClassName );
    return enclosingClassName;
//    if ( className == null ) return null;
//    if ( nestedToEnclosingClassNames.containsKey( className ) ) {
//      return nestedToEnclosingClassNames.get( className );
//    }
//    String scopedName = getClassNameWithScope( className );
//    if ( scopedName == null ) return null;
//    if ( !nestedToEnclosingClassNames.containsKey( scopedName ) ) {
//      return nestedToEnclosingClassNames.get( scopedName );
//    }
////    if ( scopedName != null ) {
////      int pos = scopedName.lastIndexOf( '.' );
////      if ( pos == -1 ) return null;
////    }
//    return null;
  }
  
  public String getClassNameWithScope( String classOrInterfaceName,
                                       boolean doTypeParameters ) {
    String typeParameters = "";
    if ( classOrInterfaceName.contains( "<" )
         && classOrInterfaceName.contains( ">" ) ) {
      typeParameters =
          classOrInterfaceName.substring( classOrInterfaceName.indexOf( '<' ) + 1,
                                          classOrInterfaceName.lastIndexOf( '>' ) ).trim();
      if ( doTypeParameters ) {
        String tpNameWithScope = getClassNameWithScope( typeParameters, true );
        if ( !Utils.isNullOrEmpty( tpNameWithScope ) ) {
          typeParameters = tpNameWithScope;
        }
      }
      typeParameters = "<" + typeParameters + ">";
      classOrInterfaceName =
          classOrInterfaceName.substring( 0, classOrInterfaceName.indexOf( '<' ) );
    }
    String classNameWithScope = getClassNameWithScope( classOrInterfaceName );
    if ( !Utils.isNullOrEmpty( classNameWithScope ) ) {
      classOrInterfaceName = classNameWithScope;
    }
    return classOrInterfaceName + typeParameters;
  }
  // Try to figure out the scope of the class name if an inner class, and return
  // the scoped class name.
  public String getClassNameWithScope( String className ) {
    if ( Utils.isNullOrEmpty( className ) ) return null;
    // Return input class name if in table.
    if ( paramTable.keySet().contains( className ) ) {
      // Note: this.paramTable is used because it is populated at the beginning.
      // this.methodTable could also be used.  this.classes cannot be used since
      // it only contains classes processed so far.
      return className;
    }
    // See if the class is an inner class of the current class
    String classNameWithScope = this.currentClass + "." + className;
    if ( paramTable.keySet().contains( classNameWithScope ) ) {
      return classNameWithScope;
    }
    
    // Loop through existing class names and find those that end with the input
    // name. Pick the one that seems to be "best" and print a warning if not
    // sure.
    String otherClassName = null;
    for ( String n : paramTable.keySet() ) {
      if ( n.endsWith( className ) &&
           ( n.length() == className.length() || 
             n.charAt( n.lastIndexOf( className ) - 1 ) == '.' ) ) {
        if ( otherClassName != null && otherClassName.endsWith( className ) ) {
          if ( n.endsWith( classNameWithScope ) ) {
            if ( otherClassName.endsWith( classNameWithScope ) ) {
              if ( n.contains( classNameWithScope ) ) {
                if ( otherClassName.contains( classNameWithScope ) ) {
                  System.err.println( "Warning! Got more than one candidate class for "
                                      + className + ": " + otherClassName + ", "
                                      + n );
                  if ( n.length() < otherClassName.length() ) {
                    otherClassName = n;
                  }
                } else {
                  otherClassName = n;
                }
              }
            } else {
              otherClassName = n;
            }
          }
        } else {
          otherClassName = n;
        }
      }
    }

//    if ( Utils.isNullOrEmpty( otherClassName ) ) {
//      otherClassName = ClassUtils.getFullyQualifiedName( className, false );
//    }
    return otherClassName;
  }

  protected Param lookupMemberByName( String className, String paramName,
                                      boolean lookOutsideXml ) {
    return lookupMemberByName( className, paramName, lookOutsideXml, true );
  }

  protected Param lookupMemberByName( String className, String paramName,
                                      boolean lookOutsideXml,
                                      boolean complainIfNotFound ) {
    if ( Debug.errorOnNull( "Passing null in lookupMemberByName(" + className
                            + ", " + paramName + ")", className, paramName ) ) {
      return null;
    }
    if ( className.equals( "this" ) ) {
      className = currentClass;
    }
    // Check if the className is known.
    Map< String, Param > params = paramTable.get( className );
    // If the name is not in the table, make sure it's the scoped name.
    String classNameWithScope = null;
    if ( params == null ) {
      classNameWithScope = getClassNameWithScope( className );
      if ( classNameWithScope != null ||
           ( !lookOutsideXml && complainIfNotFound &&
             !Debug.errorOnNull( false, "Error! Could not find a class definition for " 
                                 + className
                                 + " when looking for member " + paramName + ".",
                                 classNameWithScope ) ) ) {
//      if ( Utils.isNullOrEmpty( classNameWithScope ) ) {
        params = paramTable.get( classNameWithScope );
      }
    }
    Param p = null;
    if ( params != null ) {
      p = params.get( paramName );
    }
    // If not in the table and an inner class, check enclosing class's scope. 
    if ( p == null && isInnerClass( className ) ) {
      String enclosingClassName = getEnclosingClassName( className );
      if ( !Utils.isNullOrEmpty( enclosingClassName ) ) {
        p = lookupMemberByName( enclosingClassName, paramName, lookOutsideXml,
                                complainIfNotFound && lookOutsideXml );
      }
    }
    Class<?> classForName = null;
    if ( p == null && lookOutsideXml ) {
      classForName = ClassUtils.getClassForName( className, this.packageName, false );//, getClass().getClassLoader(), Package.getPackages() );
      if ( classForName != null ) {
        Field field = null;
        try {
          field = classForName.getField( paramName );
        } catch ( NoSuchFieldException e ) {
          // ignore
        } catch ( SecurityException e ) {
          // ignore
        }
        if ( field != null ) {
          p = new Param( paramName,
                         field.getType().getName().replace( '$', '.' ),
                         null );
        }
      }
    }
    if ( Debug.isOn() ) Debug.outln( "lookupMemberByName( className=" + className + ", paramName=" + paramName
                 + ") returning " + p );
    if ( p == null && complainIfNotFound ) {
      Debug.errorOnNull( false, "lookupMemberByName(" + className + ", "
                                + paramName + "): no parameter found\n  paramTable =\n"
                                + paramTable + "\n  enclosingClasses =\n"
                                + nestedToEnclosingClassNames, p );
    }
    return p; 
  }

  public boolean isEvent( String className ) {
    if ( Utils.isNullOrEmpty( className ) ) return false;
    Boolean s = isEventMap.get( className );
    if ( s != null && s ) return true;
    String scopedName = getClassNameWithScope( className );
    s = isEventMap.get( scopedName );
    return ( s != null && s );
  }
  
  public boolean knowIfStatic( String name ) {
    if ( Utils.isNullOrEmpty( name ) ) return true;
    Boolean s = isStaticMap.get( name );
    return s != null;
  }
  
  public boolean isStatic( String name ) {
    if ( Utils.isNullOrEmpty( name ) ) return false;
    Boolean s = isStaticMap.get( name );
    return s != null && s;
  }
  
  public boolean isClassStatic( String className ) {
    if ( isStatic( className ) ) return true;
    String scopedName = getClassNameWithScope( className );
    return isStatic( scopedName );
  }
  
  public boolean knowIfClassIsStatic( String className ) {
    if ( knowIfStatic( className ) ) return true;
    String scopedName = getClassNameWithScope( className );
    return knowIfStatic( scopedName );
  }
  
  public boolean isInnerClass( String className ) {
    // TODO -- should have a ClassDeclaration stub class to collect this info.
    boolean is = !isClassStatic( className ) && isNested( className );
    if ( Debug.isOn() ) Debug.outln( "isInnerClass( " + className + ") = " + is );
    return is;
  }

  public boolean isNested( String className ) {
    String ecn = getEnclosingClassName( className );
    boolean is = !Utils.isNullOrEmpty( ecn );
    if ( Debug.isOn() ) Debug.outln( "isNested( " + className + ") = " + is + ": "
                 + ( is ? ecn : "" ) );
    return is;
  }
  
  // TODO -- is generated Java for member getting the static tag?  
  public boolean isMemberStatic( String className, String memberName ) {
    String memberShortName = memberName;
    int pos = memberName.lastIndexOf( '.' ); 
    if ( pos >= 1 ) {
      className = memberName.substring( 0, pos );
      memberShortName = memberName.substring( pos+1 );
    }
    String tryName = className + "." + memberShortName;
    if ( isStatic( tryName ) ) return true;
    String scopedName = getClassNameWithScope( className );
    if ( !Utils.isNullOrEmpty( scopedName ) ) {
      tryName = scopedName + "." + memberShortName;
      if ( isStatic( tryName ) ) return true;
    }
    return false;
  }
  
  public boolean isMemberStatic( String memberName ) {
    String className = currentClass;
    return isMemberStatic( className, memberName );
  }
  
  
  // For each class definition, gather all locally defined and inherited
  // parameters and store them in a table indexed by the class name.
  protected void
      buildParamTable( Document doc,
                       Map< String, Map< String, Param > > paramTable ) {
    
    // Start out by adding default parameters for DurativeEvent
    // Make an entry in the table for this class.
    Map< String, Param > params = new TreeMap< String, Param >();
    paramTable.put( "DurativeEvent", params );
    DurativeEvent de = new DurativeEvent();
    for ( gov.nasa.jpl.ae.event.Parameter< ? > p : de.getParameters() ) {
      if ( !params.containsKey( p.getName() ) ) {
        String pType =
            ( p.getValueNoPropagate() == null 
              ? "Integer" // TODO -- big assumption! Use p.getClass().
              : p.getValueNoPropagate().getClass().getSimpleName() );
        params.put( p.getName(),
                    new Param( p.getName(), pType,
                               ( p.getValueNoPropagate() == null 
                                 ? null
                                 : p.getValueNoPropagate().toString() ) ) );
      }
    }

    
    List< Node > nList = XmlUtils.findNodes( doc, "class" );
    
    // Process classes except those with a super class that has not yet been
    // processed. Repeat looping through the unprocessed classes until they are
    // all processed or no more can be processed.
    int lastSize = nList.size() + 1;
    while ( !nList.isEmpty() && nList.size() < lastSize ) {
      lastSize = nList.size();
      ListIterator< Node > i = nList.listIterator();
      // Loop through unprocessed classes.
      while ( i.hasNext() ) {
        // for ( int i = 0; i < nList.size(); i++ ) {
        Node classNode = i.next(); // nList.get( i );
        String className = getClassName( classNode );
        boolean classIsStatic = isNodeOfDeclarationStatic( classNode );
        if ( Debug.isOn() ) Debug.outln( className + " is " + ( classIsStatic ? "" : "not" )
                     + " static" );
        isStaticMap.put( className, classIsStatic );
        String superClass =
            fixName( XmlUtils.getChildElementText( classNode, "inheritsFrom" ) );
        Node parentNode = classNode.getParentNode();
        boolean isAnEvent = parentNode.getLocalName().equals( "event" );
        isEventMap.put( className, isAnEvent );
        Map< String, Param > superParams = null;

        // If super class has not been processed, try again on the next pass.
        if ( superClass != null && !superClass.isEmpty() ) {
          superParams = paramTable.get( superClass );
          if ( superParams == null ) {
            continue;
          }
        }
        // Make an entry in the table for this class.
        //Map< String, Param > 
        params = paramTable.get( className );
        if ( params == null ) {
          params = new TreeMap< String, Param >();
          paramTable.put( className, params );
        }
        // Get the default DurativeEvent params if not available from superClass
        if ( isAnEvent && ( superParams == null || superParams.isEmpty() ) ) {
          assert false;
          DurativeEvent de1 = new DurativeEvent();
          for ( gov.nasa.jpl.ae.event.Parameter< ? > p : de1.getParameters() ) {
            if ( !params.containsKey( p.getName() ) ) {
              String pType =
                  ( p.getValueNoPropagate() == null 
                    ? "Integer" // TODO -- big assumption! Use p.getClass().
                    : p.getValueNoPropagate().getClass().getSimpleName() );
              params.put( p.getName(),
                          new Param( p.getName(), pType,
                                     ( p.getValueNoPropagate() == null 
                                       ? null
                                       : p.getValueNoPropagate().toString() ) ) );
            }
          }
        } else {
          // Get inherited params.
          if ( superParams != null && params != null ) {
            params.putAll( superParams );
          }
        }
        // Now add params defined locally
        Node membersNode = XmlUtils.getChildNode( classNode, "members" );
        List< Node > pNodes = XmlUtils.getChildNodes( membersNode, "parameter" );
        // NodeList nodeList = node.XmlUtils.getChildNodes();
        for ( int j = 0; j < pNodes.size(); j++ ) {
          Node pNode = pNodes.get( j );
          Param p = new Param( pNode );
          if ( isNodeOfDeclarationStatic( pNode ) ) {
            this.isStaticMap.put( className + "." + p.name, true );
          }
          Param ep = params.get( p.name );
          if ( ep == null ) {
            params.put( p.name, p );
          } else {
            // TODO -- BAD!
          }
        }
        // Remove the processed class from the list of unprocessed classes.
        i.remove();
      }
    }
  }

  public boolean isNodeOfDeclarationStatic( Node classNode ) {
    return XmlUtils.isAttributeTrue( classNode, "static" );
  }

  // Add constructors for invocations.
  private void addConstructors() {
    Collection< ConstructorDeclaration > constructors =
        getConstructorDeclarations( this.xmlDocDOM );
    constructors.addAll( createConstructors( this.xmlDocDOM, constructors ) );
    for ( ConstructorDeclaration c : constructors ) {
      TypeDeclaration type = getTypeDeclaration( c.getName() );
      boolean alreadyAdded = false;
      if ( type != null && c != null ) {
        for ( BodyDeclaration bd : type.getMembers() ) {
          if ( bd instanceof ConstructorDeclaration ) {
            if ( equals(c, (ConstructorDeclaration)bd ) ) {
              alreadyAdded = true;
              break;
            }
          }
        }
        if ( !alreadyAdded ) {
          ASTHelper.addMember( type, c );
        }
      }
    }
  }

  // REVIEW -- TODO -- Wouldn't it be better to just have a Map<name, TypeDeclaration>?
  // Recursively look for a type declaration with the given name.
  protected TypeDeclaration getTypeDeclarationFrom( String name,
                                                    TypeDeclaration typeDecl ) {
    String simpleName = ClassUtils.simpleName( name );
    if ( typeDecl.getName().equals( simpleName ) ) {
      return typeDecl;
    }
    for ( BodyDeclaration bd : typeDecl.getMembers() ) {
      if ( bd instanceof TypeDeclaration ) {
        TypeDeclaration td = getTypeDeclarationFrom( simpleName, (TypeDeclaration)bd );
        if ( td != null ) return td;
      }
    }
    return null;
  }
  
  
  // Look in the compilation units in the map, classes, to find the class
  // declaration of name.
  protected TypeDeclaration getTypeDeclaration( String name ) {
    String simpleName = ClassUtils.simpleName( name );
    if ( name == null ) return null;
    CompilationUnit cu = this.classes.get( simpleName );
    if ( cu != null ) {
      for ( TypeDeclaration type : cu.getTypes() ) {
        if ( type.getName().equals( simpleName ) ) {
          return type;
        }
      }
    } else {
      for ( CompilationUnit c : this.classes.values() ) {
        for ( TypeDeclaration t : c.getTypes() ) {
          TypeDeclaration td = getTypeDeclarationFrom( simpleName, t );
          if ( td != null ) {
            return td;
          }
        }
      }
    }
    return null;
  }

  protected void setPackage() {
    // set the package based on the xmlFileName
    // String packageName =
    // "generated."
    // + xmlFileName.substring( 0, xmlFileName.lastIndexOf( '.' ) )
    // .replaceAll( "[^A-Za-z0-9_]+", "_" );
    if ( Debug.isOn() ) Debug.outln("setting package for current compilation unit to " + packageName );
    currentCompilationUnit.setPackage( new PackageDeclaration( ASTHelper.createNameExpr( packageName ) ) );
  }

  protected ClassOrInterfaceDeclaration processEvent( Node eventNode,
                                                      boolean innerClass,
                                                      boolean justClassDeclarations ) {
    Node classNode = XmlUtils.getChildNode( eventNode, "class" );
    ClassOrInterfaceDeclaration newClassDecl =
        processClassDeclaration( classNode, eventNode, innerClass,
                                 justClassDeclarations );
    return newClassDecl;
  }

  protected void processExecutionEvent( Node invocationNode ) {
    assert( invocationNode != null );

    currentClass = "Main";
    initClassCompilationUnit( currentClass );

    ClassOrInterfaceDeclaration newClassDecl =
        new ClassOrInterfaceDeclaration( ModifierSet.PUBLIC, false,
                                         currentClass );
    ASTHelper.addTypeDeclaration( currentCompilationUnit, newClassDecl );

    // Create public static main( String args[] ) { }
    // First, create main() { }
    int mods = ModifierSet.PUBLIC | ModifierSet.STATIC;
    
    MethodDeclaration mainMethodDecl =
        new MethodDeclaration( mods, new VoidType(), "main" );
    BlockStmt mainBody = new BlockStmt();
    mainMethodDecl.setBody( mainBody );

    ConstructorDeclaration ctor =
        new ConstructorDeclaration( ModifierSet.PUBLIC, newClassDecl.getName() );
    ASTHelper.addMember( newClassDecl, ctor );
    BlockStmt ctorBody = new BlockStmt();
    ctor.setBlock( ctorBody );
    
    // Need to set the epoch and units first thing.
    // REVIEW -- We need a scenario event that requires these arguments in the
    // constructor to ensure they are set up front.
    //String epochString = Timepoint.toTimestamp( Timepoint.getEpoch().getTime() );
    addStatements( mainBody,
                   "Timepoint.setUnits(\"" + Timepoint.getUnits() + "\");\n" );
    addStatements( mainBody,
                   "Timepoint.setEpoch(\"" + Timepoint.getEpoch() + "\");\n" );
    addStatements( mainBody,
                   "Timepoint.setHorizonDuration("
                   + Timepoint.getHorizonDuration() + ");\n" );

    // Create String args[].
    Type type = ASTHelper.createReferenceType( "String", 1 );
    VariableDeclaratorId id = new VariableDeclaratorId( "args" );
    japa.parser.ast.body.Parameter parameter =
        new japa.parser.ast.body.Parameter( type, id );
    // Wire everything together. 
    ASTHelper.addParameter( mainMethodDecl, parameter  );
    ASTHelper.addMember( newClassDecl, mainMethodDecl );
    
    // Now add statements to main()
    
    // Get the name/class of the event to execute
    String className = fixName( XmlUtils.getChildElementText( invocationNode, "eventType" ) );
    String instanceName = fixName( XmlUtils.getChildElementText( invocationNode, "eventName" ) );
    if ( instanceName == null || instanceName.isEmpty() ) {
      instanceName = className + (counter++);
    }

    // The Main class will extend the event to execute.
    addExtends( newClassDecl, className );
    
    // Use a StringBuffer to collect the statements. 
    StringBuffer stmtsMain = new StringBuffer();
    //StringBuffer stmtsCtor = new StringBuffer();

    // Get constructor arguments and create a statement constructing the instance.
    stmtsMain.append( "Main scenario = new Main();");
    //stmtsSB.append( className + " " + instanceName + " = new " + className + "(");
    //stmtsCtor.append( "super(");
    Node argumentsNode = XmlUtils.getChildNode( invocationNode, "arguments" );
    //List< Param > arguments = new ArrayList< Param >();
    List< Expression > args = new ArrayList< Expression >();
    if ( argumentsNode != null ) {
      List< Node > argNodeList = XmlUtils.getChildNodes( argumentsNode, "parameter" );
      boolean first = true;
      for ( int j = 0; j < argNodeList.size(); j++ ) {
        if ( first ) {
          first = false;
        } else {
          //stmtsCtor.append( ", " );
        }
        Node argNode = argNodeList.get( j );
        Param p = new Param( argNode );
        String exprStr = javaToAeExpr( p.value, p.type, true );
        japa.parser.ast.expr.Expression expr = new NameExpr( exprStr );
        args.add( expr );
        //stmtsCtor.append( exprStr );
      }
    }
    //stmtsCtor.append(");\n");
    ASTHelper.addStmt( ctorBody, new ExplicitConstructorInvocationStmt( false, null, args ) );
    
    // Need to import event.Expression etc. for constructor arguments
    addImport( "gov.nasa.jpl.ae.event.TimeVarying" );
    addImport( "gov.nasa.jpl.ae.event.TimeVaryingMap" );
    addImport( "gov.nasa.jpl.ae.event.TimeVaryingPlottableMap" );
    addImport( "gov.nasa.jpl.ae.event.Expression" );
    addImport( "gov.nasa.jpl.ae.event.Timepoint" );
//    addImport( "event.FunctionCall" );
//    addImport( "event.Functions" );
    
    // Create statements for executing & simulating the scenario event.
    //stmtsSB.append( instanceName + ".executeAndSimulate();\n" );
    stmtsMain.append( "scenario.executeAndSimulate();\n" );
    
    // Put the statements in the constructor.
    //addStatements( ctorBody, stmtsCtor.toString() );
    
    // Put the statements in main().
    addStatements( mainBody, stmtsMain.toString() );
  }

  protected static void addExtends( ClassOrInterfaceDeclaration newClassDecl,
                                    String superClass ) {
    if ( newClassDecl.getExtends() == null ) {
      newClassDecl.setExtends( new ArrayList< ClassOrInterfaceType >() );
    }
    newClassDecl.getExtends().add( new ClassOrInterfaceType( superClass ) );
  }

//  protected BlockStmt addTryCatch( BlockStmt blockStmt,
//                                   String... exceptionNames ) {
//    
//    return null;
//  }

  private void addTryCatchToInitMembers( MethodDeclaration initMembers ) {
    TryStmt tryStmt = null;
    
    // Need to add a statement that will certainly need all of these exceptions;
    // otherwise, we'll get a compile error for trying to catch something that
    // can't be thrown.  Test code commented out below.
    String pkg = packageName + ".";
    if ( pkg.length() == 1 ) {
      pkg = "";
    }
    
    String tryCatchString =
        "try{\n" + ";\n" + "} catch ( Exception e ) {\n"
            + "  // TODO Auto-generated catch block\n"
            + "  e.printStackTrace();\n"
            + "}\n";

    List< Statement > stmts = new ArrayList< Statement >();
    if ( Debug.isOn() ) Debug.outln( "trying to parse \"" + stmts + "\"" );

    ASTParser parser = new ASTParser( new StringReader( tryCatchString ) );
    try {
      tryStmt = parser.TryStatement();
    } catch ( ParseException e ) {
      e.printStackTrace();
      return;
    }
    tryStmt.setTryBlock( initMembers.getBody() );
    stmts.add( tryStmt );
    BlockStmt newBody = new BlockStmt( stmts );
    initMembers.setBody( newBody );
  }

  protected Collection< ConstructorDeclaration > getConstructorDeclarations( Node top ) {
    Collection< ConstructorDeclaration > ctors =
        new ArrayList< ConstructorDeclaration >();
    List< Node > constructorsNodes = XmlUtils.findNodes( top, "constructors" );
    for ( Node constructorsNode : constructorsNodes ) {
      List< Node > mNodeList = XmlUtils.getChildNodes( constructorsNode, "function" );
      for ( Node mNode : mNodeList ) {
        String constructorString = fixValue( mNode.getTextContent() );
        ConstructorDeclaration constructorDecl = parseConstructorDeclaration( constructorString );
        if ( constructorDecl != null ) {
          if ( !ModifierSet.isPrivate( constructorDecl.getModifiers() )
               && !ModifierSet.isProtected( constructorDecl.getModifiers() ) ) {
            // TODO -- Let mods be specified in XML through attributes!
            constructorDecl.setModifiers( ModifierSet.addModifier( constructorDecl.getModifiers(),
                                                                   ModifierSet.PUBLIC ) );
          }
          ctors.add( constructorDecl );
        }
      }
    }
    return ctors;
  }
  
  // Create constructors for event invocations.
  protected Collection< ConstructorDeclaration > 
   createConstructors( Node top, Collection< ConstructorDeclaration > ctors ) {
    //Collection< ConstructorDeclaration > ctors =
    //    new ArrayList< ConstructorDeclaration >();

    //Debug.turnOn();
    if ( Debug.isOn() ) Debug.outln( "existing constructors: " + ctors );
    //Debug.turnOff();
    List< Node > invocations = 
        XmlUtils.findNodes( top, "eventToBeExecuted" );
    invocations.addAll( XmlUtils.findNodes( top, "eventInvocation" ) );
    for ( Node invocationNode : invocations ) {
      //String name = XmlUtils.getChildElementText( invocationNode, "eventName" );
      if ( invocationNode != null ) {
        String eventType = fixName( XmlUtils.getChildElementText( invocationNode,
                                                                  "eventType" ) );
        ConstructorDeclaration ctor =
            new ConstructorDeclaration( ModifierSet.PUBLIC,
                                        ClassUtils.simpleName(eventType) );
        if ( Debug.isOn() ) Debug.outln("ctor ctord as " + ctor.getName() );
        Node argumentsNode = XmlUtils.getChildNode( invocationNode, "arguments" );
        List< Param > arguments = new ArrayList< Param >();
        if ( argumentsNode != null ) {
          List< Node > argNodeList = XmlUtils.getChildNodes( argumentsNode,
                                                             "parameter" );
          for ( int j = 0; j < argNodeList.size(); j++ ) {
            Node argNode = argNodeList.get( j );
            arguments.add( new Param( argNode ) );
          }
          List< japa.parser.ast.body.Parameter > parameters =
              new ArrayList< japa.parser.ast.body.Parameter >();
          for ( Param p : arguments ) {
            if ( p.type == null ) {
              Param memberDecl = lookupMemberByName( eventType, p.name,
                                                     true, false );
              if ( !Debug.errorOnNull( "Error! Can't find member " + p.name
                                           + " for event class " + eventType
                                           + "!",
                                       memberDecl ) ) {
                p.type = memberDecl.type;
              }
            }
            japa.parser.ast.body.Parameter param =
                ASTHelper.createParameter( new ClassOrInterfaceType( "Expression<"
                                                                         + p.type
                                                                         + ">" ),
                                           p.name );
            parameters.add( param );
          }
          ctor.setParameters( parameters );
          addStatementsToConstructor( ctor, arguments );
        }

        // Check and see if we've already added this one.
        boolean alreadyCreated = false;
        //Debug.turnOn();
        for ( ConstructorDeclaration c : ctors ) {
          if ( equals( c, ctor ) ) {
            if ( Debug.isOn() ) Debug.outln( "constructor already created: " + ctor );
            alreadyCreated = true;
            break;
          }
        }
        //Debug.turnOff();
        // Don't add if already created. Default constructor is added
        // elsewhere, so filter that one out by checking if arguments is empty.
        if ( !alreadyCreated && !arguments.isEmpty() ) {
          ctors.add( ctor );
        }
      }
    }
    return ctors;
  }

  public static boolean equals( ConstructorDeclaration c1,
                                ConstructorDeclaration c2 ) {
    boolean localDebug = false;
    if ( localDebug ) {
      if ( Debug.isOn() ) Debug.outln( "equals(c1 = " + c1.getName() + ", c2 = " + c2.getName() );
      if ( Debug.isOn() ) Debug.outln( "equals() for c1 = \n" + c1 );
      if ( Debug.isOn() ) Debug.outln( "and c2 = \n" + c2 );
    }
    boolean equals = false;
    List< japa.parser.ast.body.Parameter > params1 = c1.getParameters();
    List< japa.parser.ast.body.Parameter > params2 = c2.getParameters();
    int paramsSize1 = (params1 == null ? 0 : params1.size() );
    int paramsSize2 = (params2 == null ? 0 : params2.size() );
    if ( c1.getName().equals( c2.getName() ) ) {
      if ( paramsSize1 == paramsSize2 ) {
        equals = true;
        for ( int i = 0; i < paramsSize1; ++i ) {
          japa.parser.ast.body.Parameter p1 = params1.get( i );
          japa.parser.ast.body.Parameter p2 = params2.get( i );
          if ( !p1.getType().toString().equals( p2.getType().toString()) ) {
            if ( localDebug ) {
              if ( Debug.isOn() ) Debug.outln( "constructors not equal; number " + i
                           + " param types do not match: "
                           + p1.getType().toString() + " != "
                           + p2.getType().toString() );
            }
            equals = false;
            break;
          }
        }
      } else {
        if ( localDebug ) {
          if ( Debug.isOn() ) Debug.outln( "constructors not equal; different numbers of params: "
                       + paramsSize1 + " != " + paramsSize2 );
        }
      }
    } else {
      if ( localDebug ) {
        if ( Debug.isOn() ) Debug.outln( "constructors not equal; different names: "
                     + c1.getName() + " != " + c2.getName() );
      }
    }
    return equals;
  }

  protected void
      addStatementsToConstructor( ConstructorDeclaration ctor,
                                  List< Param > arguments ) {
    StringBuffer stmtList = new StringBuffer();
    BlockStmt block = new BlockStmt();
    ASTHelper.addStmt( block, new ExplicitConstructorInvocationStmt() );
    // stmtList.append( "super();\n" );
    stmtList.append( "init" + ctor.getName() + "Members();\n" );
    stmtList.append( "init" + ctor.getName() + "Collections();\n" );
    for ( Param p : arguments ) {
//      if ( p.name.equals( "startTime" ) || p.name.equals( "endTime" )
//           || p.name.equals( "duration" ) ) {
//        stmtList.append( "removeDependenciesForParameter( this." + p.name
//                         + " );\n" );
//      }
      stmtList.append( "addDependency( this." + p.name + ", " + p.name
                       + " );\n" );
    }
    if ( isEvent( ctor.getName() ) ) {
      stmtList.append( "init" + ctor.getName() + "Elaborations();\n" );
      stmtList.append( "fixTimeDependencies();\n" );
    }
    if ( Debug.isOn() ) Debug.outln( "adding statements to block: " + stmtList.toString() );
    addStatements( block, stmtList.toString() );
    ctor.setBlock( block );
    // ctor.setBlock( createBlock( stmtList.toString() ) );
  }

  protected MethodDeclaration createPublicVoidMethod( String methodName ) {
    MethodDeclaration initMembers =
        new MethodDeclaration( ModifierSet.PUBLIC, new VoidType(),
                               methodName );
    initMembers.setBody( new BlockStmt() );
    return initMembers;
  }

  private static void addStmts( BlockStmt block, List< Statement > list ) {
    if ( list != null ) {
      for ( Statement stmt : list ) {
        ASTHelper.addStmt( block, stmt );
      }
    }
  }

  public static void addStatements( BlockStmt block, String stmts ) {
    if ( Debug.isOn() ) Debug.outln( "trying to parse \"" + stmts + "\"" );
    List< Statement > list = stringToStatementList( stmts );
    addStmts( block, list );
  }

  public static BlockStmt createBlock( String stmts ) {
    BlockStmt block = new BlockStmt();
    addStatements( block, stmts );
    return block;
  }

  private MethodDeclaration
      createInitCollectionsMethod( String methodName,
                                   Collection< FieldDeclaration > parameters,
                                   // Collection< FieldDeclaration > methods,
                                   Collection< FieldDeclaration > constraints,
                                   //Collection< FieldDeclaration > dependencies,
                                   Collection< Pair< String, FieldDeclaration >> effects ) {
    // Collection< FieldDeclaration > elaborations ) {
    MethodDeclaration initCollections =
        new MethodDeclaration( ModifierSet.PROTECTED, new VoidType(),
                               methodName );
    // TODO -- Add initCollections()'s body.
    BlockStmt block = new BlockStmt();
    List< Statement > stmtList = null;
    stmtList =
        createStmtsFromFieldCollection( "parameters.add( ", parameters, " );\n" );
    addStmts( block, stmtList );
    stmtList =
        createStmtsFromFieldCollection( "constraintExpressions.add( ",
                                        constraints, " );\n" );
    addStmts( block, stmtList );
//    stmtList = removeConflictingDependencies();
//    addStmts( block, stmtList );
//    stmtList =
//        createStmtsFromFieldCollection( "dependencies.add( ", dependencies,
//                                        " );\n" );
    addStmts( block, stmtList );
    // TODO -- correct for effects?
    if ( effects != null ) {
      stmtList =  
          createEffectStmtsFromFieldCollection( "addEffects( (Parameter< ? >)", effects, " );\n" );
    }
    addStmts( block, stmtList );

    initCollections.setBody( block );
    return initCollections;
  }

  public static List< Statement > stringToStatementList( String s ) {
    if ( Debug.isOn() ) Debug.outln( "trying to parse Java statements \"" + s + "\"" );
    ASTParser parser = new ASTParser( new StringReader( s ) );
    List< Statement > stmtList = null;
    try {
      stmtList = parser.Statements();
    } catch ( ParseException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return stmtList;
  }
  
//  protected List< Statement > removeConflictingDependencies() {
//    StringBuilder sb = new StringBuilder();
//    if ( gotStartTimeDependency ) {
//      sb.append( "removeDependenciesForParameter( startTime );\n" );
//    }
//    if ( gotEndTimeDependency ) {
//      sb.append( "removeDependenciesForParameter( endTime );\n" );
//    }
//    if ( gotDurationDependency ) {
//      sb.append( "removeDependenciesForParameter( duration );\n" );
//    }
//    return stringToStatementList( sb.toString() );
//  }

  private
      List< Statement >
      createStmtsFromFieldCollection( String prefix,
                                      Collection< FieldDeclaration > fieldCollection,
                                      String suffix ) {
    if ( fieldCollection == null || fieldCollection.isEmpty() ) return null;
    // private Statement createInitCollectionStmt( String collectionName,
    // Collection< FieldDeclaration > fieldCollection ) {
    StringBuilder sb = new StringBuilder();
    for ( FieldDeclaration f : fieldCollection ) {
      // sb.append("    " + collectionName + ".add( " + f.getVariables().get( 0
      // ) + " );\n" );
      sb.append( prefix + f.getVariables().get( 0 ).getId() + suffix );
    }
    return stringToStatementList( sb.toString() );
  }

  private List< Statement >
      createEffectStmtsFromFieldCollection( String prefix,
                                            Collection< Pair< String, FieldDeclaration > > fieldCollection,
                                            String suffix ) {
    Map< String, Set< FieldDeclaration > > map = new TreeMap< String, Set< FieldDeclaration > >();
    for ( Pair< String, FieldDeclaration > p : fieldCollection ) {
      if ( !p.second.getType().toString().equals( "Effect" ) ) continue;
      Set< FieldDeclaration > set = map.get( p.first );
      if ( set == null ) {
        set = new TreeSet< FieldDeclaration >(new CompareUtils.GenericComparator< FieldDeclaration >());
        map.put( p.first, set );
      }
      set.add( p.second );
    }
    boolean madeSet = false;
    if ( fieldCollection == null || fieldCollection.isEmpty() ) return null;
    StringBuilder sb = new StringBuilder();
    for ( Map.Entry< String, Set< FieldDeclaration > > e : map.entrySet() ) {
      String effectSetName = "effectsFor" + e.getKey();
      sb.append( "Set<Effect> " + effectSetName + " = new TreeSet<Effect>();\n" );
      madeSet = true;
      for ( FieldDeclaration f : e.getValue() ) {
        sb.append( effectSetName + ".add( " + f.getVariables().get( 0 ).getId() + " );\n" );
      }
      sb.append( prefix + e.getKey() + ", " + effectSetName + suffix );
    }
    if ( madeSet ) {
      addImport("java.util.Set");
      addImport("java.util.TreeSet");
      addImport("gov.nasa.jpl.ae.event.EffectFunction");
    }
    return stringToStatementList( sb.toString() );
  }

//  public ClassOrInterfaceDeclaration processClass( Node cls ) {
//    ClassOrInterfaceDeclaration newClassDecl =
//        processClassDeclaration( cls, false, true );
//    return newClassDecl;
//  }

  public static void addModifier( TypeDeclaration typeDecl, int modifier ) {
    typeDecl.setModifiers( ModifierSet.addModifier( typeDecl.getModifiers(),
                                                    modifier) );
  }
  public static void addModifier( MethodDeclaration methodDecl, int modifier ) {
    methodDecl.setModifiers( ModifierSet.addModifier( methodDecl.getModifiers(),
                                                      modifier) );
  }
  public static void addModifier( FieldDeclaration fieldDecl, int modifier ) {
    fieldDecl.setModifiers( ModifierSet.addModifier( fieldDecl.getModifiers(),
                                                     modifier) );
  }
  public static void removeModifier( TypeDeclaration typeDecl, int modifier ) {
    typeDecl.setModifiers( ModifierSet.removeModifier( typeDecl.getModifiers(),
                                                       modifier) );
  }
  public static void removeModifier( MethodDeclaration methodDecl, int modifier ) {
    methodDecl.setModifiers( ModifierSet.removeModifier( methodDecl.getModifiers(),
                                                         modifier) );
  }
  public static void removeModifier( FieldDeclaration fieldDecl, int modifier ) {
    fieldDecl.setModifiers( ModifierSet.removeModifier( fieldDecl.getModifiers(),
                                                         modifier) );
  }
  public static void makeStatic( FieldDeclaration fieldDecl ) {
    addModifier( fieldDecl, ModifierSet.STATIC );
  }
  public static void makeStatic( TypeDeclaration typeDecl ) {
    addModifier( typeDecl, ModifierSet.STATIC );
  }
  public static void makeStatic( MethodDeclaration methodDecl ) {
    methodDecl.setModifiers( ModifierSet.addModifier( methodDecl.getModifiers(),
                                                      ModifierSet.STATIC ) );
  }
  public static void makePublic( TypeDeclaration typeDecl ) {
    removeModifier( typeDecl, ModifierSet.PRIVATE );
    removeModifier( typeDecl, ModifierSet.PROTECTED );
    addModifier( typeDecl, ModifierSet.PUBLIC );
  }
  public static void makePublic( MethodDeclaration methodDecl ) {
    removeModifier( methodDecl, ModifierSet.PRIVATE );
    removeModifier( methodDecl, ModifierSet.PROTECTED );
    addModifier( methodDecl, ModifierSet.PUBLIC );
  }
  public static void makePublic( FieldDeclaration fieldDecl ) {
    removeModifier( fieldDecl, ModifierSet.PRIVATE );
    removeModifier( fieldDecl, ModifierSet.PROTECTED );
    addModifier( fieldDecl, ModifierSet.PUBLIC );
  }
  
  public ClassOrInterfaceDeclaration processClassDeclaration( Node clsNode,
                                                              Node eventNode,
                                                              boolean isNested,
                                                              boolean justClassDeclarations ) {
    // Get class name.
    currentClass = getClassName( clsNode );
    
    if ( justClassDeclarations ) if ( Debug.isOn() ) Debug.out( "pre-" );
    if ( Debug.isOn() ) Debug.outln( "processing class " + currentClass );
    if ( !isNested ) { 
      if ( justClassDeclarations ) {
        currentCompilationUnit = initClassCompilationUnit( currentClass );
      } else {
        currentCompilationUnit = classes.get( currentClass );
      }
      assert currentCompilationUnit != null;
    }
    ClassOrInterfaceDeclaration newClassDecl = null;
    if ( justClassDeclarations ) {
      newClassDecl =
          new ClassOrInterfaceDeclaration( ModifierSet.PUBLIC, false, 
                                           ClassUtils.simpleName( currentClass ) );
      if ( isClassStatic( currentClass ) ) {
        makeStatic( newClassDecl );
      }
    } else {
      newClassDecl = getClassDeclaration( ClassUtils.simpleName( currentClass ) );
    }
    
    if ( justClassDeclarations ) {
      getSuperClasses( clsNode, newClassDecl, eventNode != null );

      getImports( clsNode );
    
      if ( !isNested ) {
        ASTHelper.addTypeDeclaration( currentCompilationUnit, newClassDecl );
      }
      createDefaultConstructor( newClassDecl );
    } else {
      gotStartTimeDependency = false;
      gotEndTimeDependency = false;
      gotDurationDependency = false;
      createMembers( newClassDecl, clsNode, eventNode );
    }
    
    // inner classes
    processClassDeclarations( clsNode, newClassDecl, "events", true,
                              justClassDeclarations );
    processClassDeclarations( clsNode, newClassDecl, "classes", true,
                              justClassDeclarations );
    
    return newClassDecl;
  }

  protected void createMembers( TypeDeclaration newClassDecl, Node clsNode,
                                Node eventNode ) {
    // Get methods/functions.  
    // TODO -- If asEvent, then don't we need to convert argument types to Expressions?
    //  [Maybe no: can make into a Function expression and only pass evaluated values.]
    // TODO -- Can some args be Expressions and not others?
    // TODO -- Maybe we need to check and see whether the function is invoked with Expressions from events?  This might even determine whether class members should be Parameters.
    Collection< MethodDeclaration > methods = getMethodsForClass( currentClass );
    for ( MethodDeclaration methodDecl : methods ) {
//      if ( asEvent ) {
//        // TODO
//      } else {
        ASTHelper.addMember( newClassDecl, methodDecl );
//      }
    }

    // Need a method for initializing members and populating Event collections
    // (parameters, effects, etc.).
    MethodDeclaration initMembers =
        createPublicVoidMethod( "init" + newClassDecl.getName() + "Members" );
    MethodDeclaration initElaborations =
        createPublicVoidMethod( "init" + newClassDecl.getName() + "Elaborations" );
    MethodDeclaration initDependencies =
        createPublicVoidMethod( "init" + newClassDecl.getName() + "Dependencies" );
    addStatements( initElaborations.getBody(), "init" + newClassDecl.getName()
                                               + "Dependencies();" );

    // Get fields (parameters, constraints, & dependencies).
    // ArrayList< Parameter< ? > > members =
    List< FieldDeclaration > members = new ArrayList< FieldDeclaration >();
    Collection< FieldDeclaration > parameters =
        getParameters( XmlUtils.getChildNode( clsNode, "members" ),
                       initMembers );
    Collection< FieldDeclaration > constraints =
        getConstraints( XmlUtils.getChildNode( clsNode, "constraints" ),
                        initMembers );
    Collection< FieldDeclaration > dependencies =
        getDependencies( XmlUtils.getChildNode( clsNode, "dependencies" ),
                         initDependencies );
    Collection< Pair< String, FieldDeclaration > > effects = null;
    if ( eventNode != null ) {
      effects = getEffects( XmlUtils.getChildNode( eventNode, "effects" ),
                            initMembers );
    }
    Collection< FieldDeclaration > elaborations = null;
    if ( eventNode != null ) {
      elaborations =
          getElaborations( XmlUtils.getChildNode( eventNode, "elaborations" ),
                           initElaborations );
    }
    
    members.addAll( parameters );
    members.addAll( constraints );
    members.addAll( dependencies );
    if ( eventNode != null ) {
      for ( Pair< String, FieldDeclaration > p : effects ) {
        members.add( p.second );
      }
      members.addAll( elaborations );
    }

    addTryCatchToInitMembers(initMembers);

    // Need a method for populating Event collections (parameters, effects,
    // etc.).
    MethodDeclaration initCollections =
        createInitCollectionsMethod( "init" + newClassDecl.getName()
                                     + "Collections", parameters, // methods,
                                     constraints, //dependencies, 
                                     effects );
    // elaborations );

    // Add fields and methods to class declaration.
    for ( FieldDeclaration f : members ) {
      ASTHelper.addMember( newClassDecl, f );
    }
    ASTHelper.addMember( newClassDecl, initMembers );
    ASTHelper.addMember( newClassDecl, initCollections );
    ASTHelper.addMember( newClassDecl, initDependencies );
    ASTHelper.addMember( newClassDecl, initElaborations );

  }

  protected void createDefaultConstructor( TypeDeclaration newClassDecl ) {
    // create a default constructor that takes no arguments
    ConstructorDeclaration ctor =
        new ConstructorDeclaration( ModifierSet.PUBLIC, newClassDecl.getName() );
    ASTHelper.addMember( newClassDecl, ctor );
    BlockStmt block = new BlockStmt();
    ASTHelper.addStmt( block, new ExplicitConstructorInvocationStmt() );
    ctor.setBlock( block );
    ASTHelper.addStmt( block,
                       new MethodCallExpr( null, "init"
                                                 + newClassDecl.getName()
                                                 + "Members" ) );
    ASTHelper.addStmt( block,
                       new MethodCallExpr( null, "init"
                                                 + newClassDecl.getName()
                                                 + "Collections" ) );

    ASTHelper.addStmt( block,
                       new MethodCallExpr( null, "init"
                                                 + newClassDecl.getName()
                                                 + "Elaborations" ) );

  }
  
  protected void getSuperClasses( Node clsNode,
                                  ClassOrInterfaceDeclaration newClassDecl,
                                  boolean isEvent ) {
    List< ClassOrInterfaceType > extendsList = getInheritsFrom( clsNode );
    if ( !Utils.isNullOrEmpty( extendsList ) ) {
      newClassDecl.setExtends( extendsList );
    }
    if ( Utils.isNullOrEmpty( newClassDecl.getExtends() ) ) {
      addExtends( newClassDecl, isEvent ? "DurativeEvent"
                                       : "ParameterListenerImpl" );
    }
    List< ClassOrInterfaceType > implementsList = getImplementsFrom( clsNode );
    if ( !Utils.isNullOrEmpty( implementsList ) ) {
      newClassDecl.setImplements( implementsList );
    }
  }

  protected void getImports( Node clsNode ) {
    List< String > imports = XmlUtils.getChildrenElementText( clsNode,
                                                              "import" );
    for ( String imp : imports ) {
      addImport( imp );
    }
  }

  private void
      processClassDeclarations( Node parentNode,
                                ClassOrInterfaceDeclaration newClassDecl,
                                String classesOrEvents, boolean isNested,
                                boolean justClassDeclarations ) {
    ClassOrInterfaceDeclaration classDecl = null;
    if ( parentNode != null ) {
      String parentClassName = getClassName( parentNode );
      boolean isEvent = classesOrEvents.equals( "events" );
      String classOrEvent = ( isEvent ? "event" : "class" );
      //String classOrEvent = classesOrEvents.replaceFirst( "[e]?s$", "" );
      List< Node > nodeList = XmlUtils.getChildNodes( parentNode,
                                                      classesOrEvents );
      // NodeList nodeList = node.XmlUtils.getChildNodes();
      for ( Node childNode : nodeList ) {
        if ( parentNode != null ) {
          List< Node > classNodes = 
              XmlUtils.getChildNodes( childNode, classOrEvent );
          for ( Node classNode : classNodes ) {
            String childClassName = getClassName( classNode );
            if ( isNested ) {
              if ( justClassDeclarations ) {
                nestedToEnclosingClassNames.put( childClassName, parentClassName );
                if ( Debug.isOn() ) Debug.outln( "nestedToEnclosingClassNames.put( "
                             + childClassName + ", " + parentClassName + " )" );
              }
            } else {
              if ( Debug.isOn() ) Debug.outln( childClassName + " is not a nested class of "
                           + parentClassName );
            }
            if ( isEvent ) {
              classDecl = processEvent( classNode, isNested, justClassDeclarations );
            } else {
              classDecl = processClassDeclaration( classNode, null, isNested, justClassDeclarations );
            }
            if ( justClassDeclarations && isNested ) {
              ASTHelper.addMember( newClassDecl, classDecl );
            }
          }
        }
      }
    }
  }

  private CompilationUnit initCompilationUnit( String name ) {
    currentCompilationUnit = new CompilationUnit();
    classes.put( ClassUtils.simpleName(name), currentCompilationUnit );
    setPackage();
    return currentCompilationUnit;
  }
  
  private CompilationUnit initClassCompilationUnit( String name ) {
    currentCompilationUnit = initCompilationUnit( ClassUtils.simpleName(name) );
    // REVIEW -- How can we access eclipse's ability to auto-remove unused
    // imports?
    //addImport( "gov.nasa.jpl.ae.event.*" );
    addImport( "gov.nasa.jpl.ae.event.Parameter" );
    addImport( "gov.nasa.jpl.ae.event.IntegerParameter" );
    addImport( "gov.nasa.jpl.ae.event.DoubleParameter" );
    addImport( "gov.nasa.jpl.ae.event.StringParameter" );
    addImport( "gov.nasa.jpl.ae.event.BooleanParameter" );
    addImport( "gov.nasa.jpl.ae.event.Timepoint" );
    addImport( "gov.nasa.jpl.ae.event.Expression" );
    addImport( "gov.nasa.jpl.ae.event.ConstraintExpression" );
    addImport( "gov.nasa.jpl.ae.event.Functions" );
    addImport( "gov.nasa.jpl.ae.event.FunctionCall" );
    addImport( "gov.nasa.jpl.ae.event.ConstructorCall" );
    addImport( "gov.nasa.jpl.ae.event.Call" );
    addImport( "gov.nasa.jpl.ae.event.Effect" );
    addImport( "gov.nasa.jpl.ae.event.TimeDependentConstraintExpression" );
    addImport( "gov.nasa.jpl.ae.event.Dependency" );
    addImport( "gov.nasa.jpl.ae.event.ElaborationRule" );
    addImport( "gov.nasa.jpl.ae.event.EventInvocation" );
    addImport( "gov.nasa.jpl.ae.event.ParameterListenerImpl" );
    addImport( "gov.nasa.jpl.ae.event.Event" );
    addImport( "gov.nasa.jpl.ae.event.DurativeEvent" );
    addImport( "gov.nasa.jpl.ae.event.TimeVarying" );
    addImport( "gov.nasa.jpl.ae.event.TimeVaryingMap" );
    addImport( "gov.nasa.jpl.ae.event.TimeVaryingPlottableMap" );
    addImport( "gov.nasa.jpl.ae.util.Utils" );
    addImport( "gov.nasa.jpl.ae.util.ClassUtils" );
    addImport( "java.util.Vector" );
    addImport( "java.util.Map" );
    return currentCompilationUnit;
  }

  private void addImport( String impName ) {
    NameExpr ne = new NameExpr( impName );
    ImportDeclaration d = new ImportDeclaration( ne, false, false );
    if ( currentCompilationUnit.getImports() == null ) {
      currentCompilationUnit.setImports( new ArrayList< ImportDeclaration >() );
    }
    // check for duplicates -- REVIEW - inefficient linear search
    // TODO -- never finds duplicates!
    for ( ImportDeclaration i : currentCompilationUnit.getImports() ) {
      if ( i.getName().getName().equals( impName ) ) return;
    }
    currentCompilationUnit.getImports().add( d );
  }

  public List< ClassOrInterfaceType > getInheritsFrom( Node cls ) {
    List< String > extendsStringList =
        XmlUtils.getChildrenElementText( cls, "extends" );
    extendsStringList.addAll( 
        XmlUtils.getChildrenElementText( cls, "inheritsFrom" ) );
    List< ClassOrInterfaceType > extendsList =
        new ArrayList< ClassOrInterfaceType >();
    for ( String e : extendsStringList ) {
      ClassOrInterfaceType c = new ClassOrInterfaceType( fixName( e ) );
      extendsList.add( c );
    }
    return extendsList;
  }
  
  public List< ClassOrInterfaceType > getImplementsFrom( Node cls ) {
    List< String > implementsStringList =
        XmlUtils.getChildrenElementText( cls, "implements" );
    List< ClassOrInterfaceType > implementsList =
        new ArrayList< ClassOrInterfaceType >();
    for ( String e : implementsStringList ) {
      ClassOrInterfaceType c = new ClassOrInterfaceType( fixName( e ) );
      implementsList.add( c );
    }
    return implementsList;
  }
  
  // Returns input DOM node if it has a localName "class," the closest parent
  // "class" node, or null.
  public String getEnclosingClassName( Node node ) {
    Node classNode = XmlUtils.getEnclosingNodeWithName( node, "class" );
    return getClassName( classNode );
  }

  // TODO -- Do we need to add the "owner" event as currently required for the
  // Parameter constructors?
  public static
      FieldDeclaration
      createFieldOfGenericType( String name, String typeName,
                                String parameterTypeName, String constructorArgs ) {
    String fieldTypeName = typeName;
    if ( !Utils.isNullOrEmpty( parameterTypeName ) ) {
      fieldTypeName += "< " + parameterTypeName + " >";
    }
    ClassOrInterfaceType fieldType = new ClassOrInterfaceType( fieldTypeName );
    FieldDeclaration f = null;
    VariableDeclaratorId id = new VariableDeclaratorId( name );
    Expression init = null;
    String initValue = null;
    if ( constructorArgs == null ) {
      initValue = "null";
    } else {
      initValue = "new " + typeName;
      if ( !Utils.isNullOrEmpty( parameterTypeName ) ) {
        initValue += "< " + parameterTypeName + " >";
      }
      initValue += "( " + constructorArgs + " )";
    }
    init = new NameExpr( initValue );
    VariableDeclarator variable = new VariableDeclarator( id, init );
    f =
        ASTHelper.createFieldDeclaration( ModifierSet.PUBLIC, fieldType,
                                          variable );
    return f;
  }

  public static Statement
      createAssignmentOfGenericType( String name, String typeName,
                                     String parameterTypeName,
                                     String constructorArgs ) {
    StringBuffer stmtsString = new StringBuffer();
    stmtsString.append( "if ( " + name + " == null ) " );
    stmtsString.append( name + " = " );
    if ( constructorArgs == null ) {
      stmtsString.append( "null;" );
    } else {
      stmtsString.append( "new " + typeName );
      if ( !Utils.isNullOrEmpty( parameterTypeName ) ) {
        stmtsString.append( "< " + parameterTypeName + " >" );
      }
      stmtsString.append( "( " + constructorArgs + " );" );
    }

    if ( Debug.isOn() ) Debug.outln( "Trying to parse assignment with ASTParser.BlockStatement(): \""
                        + stmtsString.toString() + "\"" );
    ASTParser parser =
        new ASTParser( new StringReader( stmtsString.toString() ) );
    Statement stmt = null;
    try {
      stmt = parser.BlockStatement();
    } catch ( ParseException e ) {
      e.printStackTrace();
    }

    return stmt;
  }

  public static FieldDeclaration
      createFieldOfGenericType( String name, String typeName,
                                String constructorArgs ) {
    return createFieldOfGenericType( name, typeName, null, constructorArgs );
  }

  public String[] convertToEventParameterTypeAndConstructorArgs( Param p ) {
    return convertToEventParameterTypeAndConstructorArgs(p, currentClass);
  }
  // REVIEW -- Do we need to specify a domain in the XML? look up a domain based
  // on p.type?
  public String[] convertToEventParameterTypeAndConstructorArgs( Param p, String classOfParameterName ) {
    String ret[] = new String[ 3 ];
    if ( p.type == null || p.type.isEmpty() || p.type.equalsIgnoreCase( "null" ) ) {
      Param pDef = this.lookupMemberByName( classOfParameterName, p.name, true, true );
      if ( pDef != null ) {
        p.type = pDef.type;
      }
    }
    String type = "Parameter";
    String parameterTypes = p.type;
    
    if ( p.type.equals( "Generation" ) ) {
      Debug.out( "" );
    }
    
    //parameterTypes = getFullyQualifiedName( parameterTypes, true );
    parameterTypes = getClassNameWithScope( parameterTypes, true );
    String castType = parameterTypes;
    if ( Utils.isNullOrEmpty( p.value ) ) {
      p.value = "null";
    }
    // TODO -- REVIEW -- Why is p.value in args by default, but recognized types
    // do not include p.value?
    String valueArg = javaToAeExpr( p.value, p.type, false, true );
    String typePlaceholder = "!TYPE!";
//    if ( valueArg.equals( "null" )
//         || ( valueArg.startsWith( "new Expression" ) &&
//              valueArg.endsWith( "(null)" ) ) ) {
      valueArg = "(" + typePlaceholder + ")" + valueArg; // replacing !TYPE! later
//    }
    String args = "\"" + p.name + "\", null, " + valueArg + ", this";
    String parameterClass = typeToParameterType( p.type );
    if ( Utils.isNullOrEmpty( p.type ) ) {
      System.err.println( "Error! creating a field " + p + " of unknown type!" );
    } else if ( !parameterClass.equals( p.type ) ) {
      type = parameterClass;
      if ( !type.equals( "Parameter" ) ) {
        parameterTypes = null;
        if ( !Utils.isNullOrEmpty( castType ) ) {
          args = "\"" + p.name + "\", " + valueArg + ", this";
        }
      }
    } else if ( p.type.toLowerCase().equals( "time" ) ) {
      type = "Timepoint";
      parameterTypes = null;
      //args = "\"" + p.name + "\", this";
      if ( !Utils.isNullOrEmpty( castType ) ) {
        args = "\"" + p.name + "\", " + valueArg + ", this";
      }
    } else if ( p.type.toLowerCase().startsWith( "int" )
                || p.type.toLowerCase().startsWith( "long" ) // TODO -- Need a
                                                             // LongParameter
                || p.type.trim().replaceAll( " ", "" )
                         .equals( "Parameter<Integer>" ) ) {
      type = "IntegerParameter";
      parameterTypes = null; // "Integer";
      //args = "\"" + p.name + "\", this";
      if ( !Utils.isNullOrEmpty( castType ) ) {
        args = "\"" + p.name + "\", " + valueArg + ", this";
      }
    } else if ( p.type.toLowerCase().equals( "double" )
                || p.type.trim().replaceAll( " ", "" )
                         .equals( "Parameter<Double>" ) ) {
      type = "DoubleParameter";
      parameterTypes = null;
      //args = "\"" + p.name + "\", this";
      if ( !Utils.isNullOrEmpty( castType ) ) {
        args = "\"" + p.name + "\", " + valueArg + ", this";
      }
    } else if ( p.type.toLowerCase().equals( "boolean" )
                || p.type.trim().replaceAll( " ", "" )
                         .equals( "Parameter<Boolean>" ) ) {
      type = "BooleanParameter";
      parameterTypes = null;
      //args = "\"" + p.name + "\", this";
      if ( !Utils.isNullOrEmpty( castType ) ) {
        args = "\"" + p.name + "\", " + valueArg + ", this";
      }
    } else if ( p.type.equals( "String" )
                || p.type.trim().replaceAll( " ", "" )
                         .equals( "Parameter<String>" ) ) {
      type = "StringParameter";
      parameterTypes = null;
      //args = "\"" + p.name + "\", this";
//    } else if ( p.type.startsWith( "TimeVaryingMap" ) ) {
//      args = "\"" + p.name + "\", this";
    }
    if ( Utils.isNullOrEmpty( castType ) ) {
      typePlaceholder = "(" + typePlaceholder + ")";
      args = args.replace( typePlaceholder, "" );
    } else {
      args = args.replace( typePlaceholder, castType );
    }
    
    // HACK -- TODO
    if ( args.contains(", new FunctionCall" ) ) {
      args += ", true";
    }
    
    ret[ 0 ] = type;
    ret[ 1 ] = parameterTypes;
    ret[ 2 ] = args;
    return ret;
  }

  public String getFullyQualifiedName( String classOrInterfaceName, boolean doTypeParameters ) {
    String typeParameters = "";
    if ( classOrInterfaceName.contains( "<" )
         && classOrInterfaceName.contains( ">" ) ) {
      typeParameters =
          classOrInterfaceName.substring( classOrInterfaceName.indexOf( '<' ) + 1,
                                          classOrInterfaceName.lastIndexOf( '>' ) ).trim();
      typeParameters = "<" + ( doTypeParameters
                               ? getFullyQualifiedName( typeParameters, true )
                               : typeParameters ) + ">";
      classOrInterfaceName =
          classOrInterfaceName.substring( 0, classOrInterfaceName.indexOf( '<' ) );
    }
    String n = ClassUtils.getFullyQualifiedName( classOrInterfaceName, false );
    if ( Utils.isNullOrEmpty( n ) || n.equals( classOrInterfaceName ) ) {
      n = getClassNameWithScope( classOrInterfaceName );
    }
    if ( Utils.isNullOrEmpty( n ) || n.equals( classOrInterfaceName ) ) {
      n = getClassNameWithScope( ClassUtils.simpleName( classOrInterfaceName ) );
    }
    if ( Utils.isNullOrEmpty( n ) || n.equals( classOrInterfaceName ) ) {
      n = classOrInterfaceName;
    }
    n = n + typeParameters;
    if ( Debug.isOn() ) Debug.outln( "getFullyQualifiedName(" + classOrInterfaceName + ", "
                 + doTypeParameters + ") = " + n );
    return n;
  }

  public FieldDeclaration createParameterField( Param p ) {
    String args[] = convertToEventParameterTypeAndConstructorArgs( p );
    // return createFieldOfGenericType( p.name, type, p.type, args );
    return createFieldOfGenericType( p.name, args[ 0 ],
                                     null,//args[ 1 ],
                                     args[ 2 ] );
  }

  public FieldDeclaration createParameterField( Param p,
                                                MethodDeclaration initMembers ) {
    if ( initMembers == null ) {
      return createParameterField( p );
    }
    String args[] = convertToEventParameterTypeAndConstructorArgs( p );
    Statement s =
        createAssignmentOfGenericType( p.name, args[ 0 ],
                                       args[ 1 ],
                                       args[ 2 ] );
    ASTHelper.addStmt( initMembers.getBody(), s );
    FieldDeclaration f =
        createFieldOfGenericType( p.name, args[ 0 ],
                                  args[ 1 ],
                                  null );
    if ( isMemberStatic( p.name ) ) {
      makeStatic( f );
    }
    return f;
  }

  public void addParameterField( TypeDeclaration typeDecl, Param p ) {
    FieldDeclaration f = createParameterField( p );
    ASTHelper.addMember( typeDecl, f );
  }

  private String operatorResultType( BinaryExpr.Operator o, String argType1,
                                     String argType2 ) {
    switch ( o ) {
      case or: // ||
      case and: // &&
      case equals: // ==
      case notEquals: // !=
      case less: // <
      case greater: // >
      case lessEquals: // <=
      case greaterEquals: // >=
        return "Boolean";
      case binOr: // |
      case binAnd: // &
      case xor: // ^
      case lShift: // <<
      case rSignedShift: // >>
      case rUnsignedShift: // >>>
      case plus: // +
      case minus: // -
      case times: // *
      case divide: // /
      case remainder: // %
      default:
        return dominantType( argType1, argType2 );
    }
  }

  private static String dominantType( String argType1, String argType2 ) {
    if ( argType1 == null ) return argType2;
    if ( argType2 == null ) return argType1;
    if ( argType1.equals( "String" ) ) return argType1;
    if ( argType2.equals( "String" ) ) return argType2;
    if ( argType1.toLowerCase().equals( "double" ) ) return argType1;
    if ( argType2.toLowerCase().equals( "double" ) ) return argType2;
    if ( argType1.toLowerCase().startsWith( "long" ) ) return argType1;
    if ( argType2.toLowerCase().startsWith( "long" ) ) return argType2;
    if ( argType1.toLowerCase().startsWith( "int" ) ) return argType1;
    if ( argType2.toLowerCase().startsWith( "int" ) ) return argType2;
    return argType1;
  }

  // TODO -- should probably import and do a switch on all classes in
  // japa.parser.ast.expr.*
  public String astToAeExprType( Expression expr, boolean lookOutsideXml,
                                 boolean complainIfNotFound ) {
    Param p = null;
    String name = null;
    String result = null;
    if ( expr == null ) return null;
    String className = expr.getClass().getSimpleName();
    // Inefficient string compare.
    if ( Debug.isOn() ) Debug.outln( "starting astToAeExprType(" + className + ":" + expr + ")" );
    if ( expr.getClass() == ConditionalExpr.class ) {
        ConditionalExpr ce = ( (ConditionalExpr)expr );

        result =
            dominantType( astToAeExprType( ce.getThenExpr(), lookOutsideXml, complainIfNotFound ),
                          astToAeExprType( ce.getElseExpr(), lookOutsideXml, complainIfNotFound ) );
    } else if ( expr.getClass() == ArrayCreationExpr.class ) {
      ArrayCreationExpr be = ( (ArrayCreationExpr)expr );
      result = be.getType().toString();
    } else if ( expr.getClass() == BinaryExpr.class ) {
        BinaryExpr be = ( (BinaryExpr)expr );
        result =
            operatorResultType( be.getOperator(),
                                astToAeExprType( be.getLeft(), lookOutsideXml, complainIfNotFound ),
                                astToAeExprType( be.getRight(), lookOutsideXml, complainIfNotFound ) );
    } else if ( expr.getClass() == UnaryExpr.class ) {
        UnaryExpr ue = ( (UnaryExpr)expr );
        result =
            operatorResultType( ue.getOperator(),
                                astToAeExprType( ue.getExpr(), lookOutsideXml, complainIfNotFound ) );
    } else if ( expr.getClass() == EnclosedExpr.class ) {
        result = astToAeExprType( ( (EnclosedExpr)expr ).getInner(), lookOutsideXml, complainIfNotFound );
    } else if ( expr.getClass() == MethodCallExpr.class ) {
      // don't worry about it--special purpose code is called later for this
      result = "null";
      if ( Debug.isOn() ) Debug.outln( "javaToEventExpressionType(" + expr + ") = " + result
                   + "; ok for MethodCallExpr!" );
      complainIfNotFound = false;
    } else if ( expr.getClass() == NameExpr.class ) {
      name = ( (NameExpr)expr ).getName();
//      below doesn't work.
//      if ( name == "True" ) name = "true";
//      if ( name == "False" ) name = "false";
    } else if ( expr.getClass() == ThisExpr.class ) {
      result = currentClass;
    } else if ( expr.getClass() == FieldAccessExpr.class ) {
      FieldAccessExpr fieldAccessExpr = (FieldAccessExpr)expr;
      // The member/field type is defined in its parent's class, and the parent class can be found by getting the type of the FiedAccessExpr's scope. 
      //if ( fieldAccessExpr.getScope() instanceof FieldAccessExpr ) {
      String parentType = astToAeExprType( fieldAccessExpr.getScope(),
                                           lookOutsideXml, false );
      if ( !Utils.isNullOrEmpty( parentType ) ) {
        p = lookupMemberByName( parentType, fieldAccessExpr.getField(),
                                lookOutsideXml, false );
      }
      //}
      if ( p == null ) {
        // If the member is static, then the scope is a class name, and we can
        // try looking it up.  // TODO -- Check to see if it's static.
        p = lookupMemberByName( fieldAccessExpr.getScope().toString(),
                                fieldAccessExpr.getField(), lookOutsideXml,
                                false );
      }
      if ( p != null ) {
        result = p.type;
      } else {
        // Maybe it's not a field access, but an enclosed class.
        if ( Utils.isNullOrEmpty( parentType ) ) {
          parentType = fieldAccessExpr.getScope().toString();
        }
        Class< ? > classForName =
            ClassUtils.getClassOfClass( parentType,
                                   fieldAccessExpr.getField().toString(),
                                   packageName, false );
        if ( classForName != null ) {
          result = classForName.getName();
        }
      }
      if ( result == null ) {
        // REVIEW -- This probably won't work! What case is this?
        if ( Debug.isOn() ) Debug.err( "Can't determine type from FieldAccessExpr: " + expr );
        name = expr.toString();
      }
    } else if ( expr.getClass() == ObjectCreationExpr.class ) {
      ObjectCreationExpr oce = (ObjectCreationExpr)expr;
      result = oce.getType().toString();
    } else if ( expr.getClass() == ClassExpr.class ) {
      ClassExpr ce = (ClassExpr)expr;
      //String pType = astToAeExprType( ce.getType(), lookOutsideXml, complainIfNotFound );
      String c = getClassNameWithScope( ce.getType().toString(), true );
      if ( Utils.isNullOrEmpty( c ) ) {
        Class<?> cc = ClassUtils.getClassForName( ce.getType().toString(), packageName, false );
        if ( cc != null ) {
          c = cc.getName();
        } else {
          c = "?";
        }
      }
      result = "Class<" + c + ">";
    } else {
        if ( className.endsWith( "LiteralExpr" ) ) {
          // get the part before "LiteralExpr"
          String typeOfLiteral =
              className.substring( 0, className.length() - 11 );
          if ( typeOfLiteral.equals( "Null" ) ) {
            result = "null"; // BAD!  REVIEW -- Do we want void or String?
          } else {
            result = typeOfLiteral;
          }
        } else if ( className.contains( "Literal" ) ) {
          result = className.substring( 0, className.indexOf( "Literal" ) );
        }
        name = expr.toString();
    }
    if ( result == null &&  name != null ) {
      if ( name.startsWith( "\"" ) ) {
        result = "String";
      } else {
        p = lookupCurrentClassMember( name, lookOutsideXml, complainIfNotFound );
        result = ( p == null ) ? null : p.type;
      }
    }
    if ( complainIfNotFound && Utils.isNullOrEmpty( result ) ) // delete this line -- just for setting breakpoint
      Debug.errorOnNull( "Error! null type for expression " + expr + "!", result );
    if ( Debug.isOn() ) Debug.outln( "javaToEventExpressionType(" + expr + ") = " + result );
    // Nested type cannot be referenced by its binary name.
    if ( result != null ) result = result.replace( '$', '.' );
    return result;
  }

  private Param lookupCurrentClassMember( String name,
                                          boolean lookOutsideXmlForTypes,
                                          boolean complainIfNotFound ) {
    return lookupMemberByName( currentClass, name, lookOutsideXmlForTypes,
                               complainIfNotFound );
  }

  private Param lookupCurrentClassMember( String name, boolean complainIfNotFound ) {
    return lookupMemberByName( currentClass, name, false, complainIfNotFound );
  }

  private String
      operatorResultType( UnaryExpr.Operator operator, String argType ) {
    return argType;
  }
  public String nameExprToAe( NameExpr nameExpr,
                              boolean wrapInFunction,
                              boolean evaluateCall,
                              boolean getParameterValue,
                              boolean propagate ) {
    if ( !getParameterValue ) return nameExpr.getName();
    String aeString = nameExpr.getName();
    Param p = lookupCurrentClassMember( aeString, false, false );
    if ( p == null ) {
      return aeString;
    }
    if ( wrapInFunction ) {
      aeString =
          "new FunctionCall(" + aeString + ", Parameter.class, \"getValue\", "
              + "new Object[]{ true })";
      if ( evaluateCall ) {
        aeString = "(" + aeString + ").evaluate(" + propagate + ")";
      }
    } else {
      aeString += ".getValue(" + propagate + ")";
    }
    return aeString;
  }

  
  public String fieldExprToAe( FieldAccessExpr fieldAccessExpr,
                               boolean lookOutsideXmlForTypes,
                               boolean complainIfDeclNotFound,
                               boolean wrapInFunction,
                               boolean evaluateCall,
                               boolean getParameterValue,
                               boolean propagate ) {
    String aeString = null;
    if ( fieldAccessExpr.getScope() != null
         && ( fieldAccessExpr.getScope() instanceof FieldAccessExpr || fieldAccessExpr.getScope() instanceof NameExpr ) ) {
      String parentType =
          astToAeExprType( fieldAccessExpr.getScope(), lookOutsideXmlForTypes, true );
      if ( !Utils.isNullOrEmpty( parentType ) ) {
        Param p =
            lookupMemberByName( parentType, fieldAccessExpr.getField(), false,
                                false );
        String parentString = null;
        if ( fieldAccessExpr.getScope() instanceof FieldAccessExpr ) {
          parentString = fieldExprToAe( (FieldAccessExpr)fieldAccessExpr.getScope(),
                                        lookOutsideXmlForTypes,
                                        complainIfDeclNotFound,
                                        wrapInFunction, false, true,
                                        propagate );
        } else {
          parentString =
              nameExprToAe( (NameExpr)fieldAccessExpr.getScope(),
                            wrapInFunction, evaluateCall, !wrapInFunction,
                            propagate );
        }
        if ( wrapInFunction ) {
          aeString =
              "new FunctionCall(" + parentString + ", Parameter.class, \"getMember\", "
                  + "new Object[]{\"" + fieldAccessExpr.getField() + "\"})";
        } else {
          aeString = parentString + "." + fieldAccessExpr.getField();
        }
        if ( p != null && getParameterValue ) {
          if ( wrapInFunction ) {
            // nesting function calls
            aeString =
                "new FunctionCall(null, Parameter.class, \"getValue\", "
                    + "new Object[]{ true }, " + aeString + ")";
          } else {
            aeString += ".getValue(" + propagate + ")";
          }
        }
        if ( wrapInFunction && evaluateCall ) {
          aeString = "(" + aeString + ").evaluate(" + propagate + ")";
        }
      }
    } else if ( fieldAccessExpr.getScope() instanceof ThisExpr ) {
      aeString = fieldAccessExpr.toString();
    }
    return aeString;
  }
  
  public String astToAeExpr( Expression expr, String type,
                             boolean convertFcnCallArgsToExprs,
                             boolean lookOutsideXmlForTypes,
                             boolean complainIfDeclNotFound ) {
    return astToAeExpr( expr, type, convertFcnCallArgsToExprs,
                        lookOutsideXmlForTypes, complainIfDeclNotFound, false );
  }
  public String astToAeExpr( Expression expr, String type,
                             boolean convertFcnCallArgsToExprs,
                             boolean lookOutsideXmlForTypes,
                             boolean complainIfDeclNotFound,
                             boolean evaluateCall ) {
    type = typeToClass( type );
    if ( Utils.isNullOrEmpty( type ) ) {
      type = astToAeExprType( expr, lookOutsideXmlForTypes, true );
    }
    final String prefix =
        "new Expression" + ( Utils.isNullOrEmpty( type ) ? "" : "<" + type + ">" ) + "( ";
    final String suffix = " )";
    String middle = null;
    /*** BinaryExpr ***/
    if ( expr.getClass() == BinaryExpr.class ) {
        BinaryExpr be = ( (BinaryExpr)expr );
        middle =
          "(new Functions."
               + javaBinaryOpToEventFunctionName( be.getOperator() ) + "( "
               + astToAeExpr( be.getLeft(), convertFcnCallArgsToExprs,
                              lookOutsideXmlForTypes,
                              complainIfDeclNotFound ) + ", "
               + astToAeExpr( be.getRight(), 
                              convertFcnCallArgsToExprs,
                              lookOutsideXmlForTypes,
                              complainIfDeclNotFound)  + " )).functionCall";
    } else
    /*** UnaryExpr ***/
    if ( expr.getClass() == UnaryExpr.class ) {
        UnaryExpr ue = ( (UnaryExpr)expr );
        // middle =
        return "new Functions."
               + astUnaryOpToEventFunctionName( ue.getOperator() ) + "( "
               + astToAeExpr( ue.getExpr(), type,
                              convertFcnCallArgsToExprs, lookOutsideXmlForTypes,
                              complainIfDeclNotFound ) + " )";
    } else
    /*** EnclosedExpr ***/
    if ( expr.getClass() == EnclosedExpr.class ) {
        middle =
            astToAeExpr( ( (EnclosedExpr)expr ).getInner(), type,
                         convertFcnCallArgsToExprs, lookOutsideXmlForTypes,
                         complainIfDeclNotFound);
    /*** NameExpr ***/
    } else if ( expr.getClass() == NameExpr.class ) {
      middle = nameExprToAe( (NameExpr)expr, true, evaluateCall, false, true );
    /*** ThisExpr ***/
    } else if ( expr.getClass() == ThisExpr.class ) {
      middle = expr.toString(); // just "this", right?
    /*** FieldAccessExpr ***/
    } else if ( expr.getClass() == FieldAccessExpr.class ) {
      FieldAccessExpr fieldAccessExpr = (FieldAccessExpr)expr;
      middle = fieldExprToAe( fieldAccessExpr, lookOutsideXmlForTypes,
                              complainIfDeclNotFound, true, evaluateCall,
                              false, true );
      /*
      //Param p = null;
      
//      if (!Utils.isNullOrEmpty( type ) ) {
//        p = lookupMemberByName( type,
//                                fieldAccessExpr.getField(),
//                                lookOutsideXmlForTypes );
//        if ( p != null ) {
//          // What were we going to do here??
//        }
//      }
      // If the scope is also a member, then we should have a parameter for it,
      // in which case we should call getValue().
      if ( fieldAccessExpr.getScope() != null
           && ( fieldAccessExpr.getScope() instanceof FieldAccessExpr
                || fieldAccessExpr.getScope() instanceof NameExpr ) ) {
        String parentType = astToAeExprType( fieldAccessExpr.getScope(),
                                             lookOutsideXmlForTypes );
        if ( !Utils.isNullOrEmpty( parentType ) ) {
          Param p = lookupMemberByName( parentType, fieldAccessExpr.getField(),
                                        false, false );
          String parentString =
              astToAeExpr( fieldAccessExpr.getScope(), parentType, false,
                           lookOutsideXmlForTypes, complainIfDeclNotFound );
//          middle = "((" + parentType + ")" + parentString + ".getValue(true))."
//              + fieldAccessExpr.getField().toString()
//              + ( ( p != null && !convertFcnCallArgsToExprs ) ? ".getValue(true)"
//                                                              : "" );
          String obj = parentString;
//          if ( !Utils.isNullOrEmpty( type ) ) {
//            obj = "(" + type + ")(" + parentString + ")";
//          }
          middle = "new FunctionCall(" + obj + ", Parameter.class, \"getMember\", "
              + "new Object[]{\"" + fieldAccessExpr.getField().toString()
              + "\"})";
          if ( ( p == null || convertFcnCallArgsToExprs ) ) {
//            middle = "((" + type + ")" + parentString + ".getMember(\""
//                + fieldAccessExpr.getField().toString() + "\"))";
          } else {
            // nesting function calls
            middle = "new FunctionCall(null, Parameter.class, \"getValue\", "
                     + "new Object[]{ true }, " + middle + ")";
//            middle = "(((Parameter<?>)(" + parentString + ".getMember(\""
//                     + fieldAccessExpr.getField().toString() + "\")"
//                     + ")).getValue(true))";
          }
          if ( evaluateCall ) {
            middle = "(" + middle + ").evaluate(true)"; 
          }
        }
      } else if ( fieldAccessExpr.getScope() instanceof ThisExpr ) {
        middle = expr.toString();
      }
      */
    /*** AssignExpr ***/
    } else if ( expr.getClass() == AssignExpr.class ) {
        AssignExpr ae = (AssignExpr)expr;
        String result = null;
        if ( ae.getOperator() == AssignExpr.Operator.assign ) {
          result =
              ae.getTarget().toString() + ".setValue( "
                  + astToAeExpr( ae.getValue(), convertFcnCallArgsToExprs,
                                 lookOutsideXmlForTypes,
                                 complainIfDeclNotFound ) + " )";
          return result;
        }
        BinaryExpr abe = new BinaryExpr();
        abe.setLeft( ae.getTarget() );
        abe.setRight( ae.getValue() );
        abe.setOperator( assignOpToBinaryOp( ae.getOperator() ) );
        Param p = lookupCurrentClassMember( ae.getTarget().toString(),
                                            lookOutsideXmlForTypes, false );
        if ( p != null ) {
          Assert.assertNotNull( abe.getOperator() );
          result =
              ae.getTarget().toString() + ".setValue( "
                  + astToAeExpr( abe, convertFcnCallArgsToExprs,
                                 lookOutsideXmlForTypes,
                                 complainIfDeclNotFound ) + " )";
          return result;
        }
        middle = ae.toString();
    /*** MethodCallExpr ***/
    /*** ObjectCreationExpr ***/
    } else if ( expr.getClass() == MethodCallExpr.class ||
                expr.getClass() == ObjectCreationExpr.class ) {
        JavaForFunctionCall javaForFunctionCall =
            new JavaForFunctionCall( this, expr, convertFcnCallArgsToExprs,
                                     packageName, evaluateCall );
        //if ( convertFcnCallArgsToExprs ) {
          middle = javaForFunctionCall.toNewFunctionCallString();
//        } else {
//          if ( Utils.isNullOrEmpty( javaForFunctionCall.getScope() ) ) {
//          middle = javaForFunctionCall.getScope() + ".";
//          if (javaForFunctionCall.methodOrConstructor) {
//            middle += javaForFunctionCall.callName + "(" + javaForFunctionCall.argumentArrayJava + ")";
//          } else {
//            middle += "new " javaForFunctionCall.callName + "(" + javaForFunctionCall.argumentArrayJava + ")";            
//          }
//        }
    } else  { //if ( expr.getClass() == ConditionalCallExpr.class ) {
      //case "ConditionalExpr": // TODO
        middle = expr.toString();
    }
    if ( !convertFcnCallArgsToExprs ) {
      return middle;
    }
    return prefix + middle + suffix;
  }

  public String getObjectFromScope( Expression scopeExpr ) {
    String object = null;
    if ( scopeExpr != null) {
      object = astToAeExpr( scopeExpr, false, true, false );
    }
    if ( object == null ) object = "null";
    return object;
  }

  public static BinaryExpr.Operator assignOpToBinaryOp( AssignExpr.Operator ao ) {
    // REVIEW -- pull this out and make it a static array;
    BinaryExpr.Operator bo = null;
    switch ( ao ) {
      case assign:
        System.err.println( "Error! Trying to translate an assignment with the '=' operator to a binary operator." );
        break; // leave null
      case plus:
        bo = BinaryExpr.Operator.plus;
        break;
      case minus:
        bo = BinaryExpr.Operator.minus;
        break;
      case star:
        bo = BinaryExpr.Operator.times;
        break;
      case slash:
        bo = BinaryExpr.Operator.divide;
        break;
      case and:
        bo = BinaryExpr.Operator.and;
        break;
      case or:
        bo = BinaryExpr.Operator.or;
        break;
      case xor:
        bo = BinaryExpr.Operator.xor;
        break;
      case rem:
        bo = BinaryExpr.Operator.remainder;
        break;
      case lShift:
        bo = BinaryExpr.Operator.lShift;
        break;
      case rSignedShift:
        bo = BinaryExpr.Operator.rSignedShift;
        break;
      case rUnsignedShift:
        bo = BinaryExpr.Operator.rUnsignedShift;
        break;
      default:
        System.err.println( "Error! Trying to translate an unknow assignment operator "
                            + ao + " to a binary operator." );
    }
    return bo;
  }

  public String astToAeExpr( Expression expr,
                             boolean convertFcnCallArgsToExprs,
                             boolean lookOutsideXmlForTypes,
                             boolean complainIfDeclNotFound ) {
    return astToAeExpr( expr, null, convertFcnCallArgsToExprs,
                        lookOutsideXmlForTypes, complainIfDeclNotFound );
  }

  private static String
      astUnaryOpToEventFunctionName( UnaryExpr.Operator operator ) {
    return "" + Character.toUpperCase( operator.toString().charAt( 0 ) )
           + operator.toString().substring( 1 );
  }

  // \([A-Za-z]*\), //\(.*\)
  // case \1: // \2
  private static String
      javaBinaryOpToEventFunctionName( BinaryExpr.Operator operator ) {
    return "" + Character.toUpperCase( operator.toString().charAt( 0 ) )
           + operator.toString().substring( 1 );
  }
  
  public static String typeToClass( String type ) {
    
    if ( Utils.isNullOrEmpty( type ) ) {
      //type = "null";
    } else if ( type.toLowerCase().equals( "time" ) 
                || type.toLowerCase().startsWith( "int" )
                || type.toLowerCase().startsWith( "long" ) ) {
      type = "Integer";
    } else if ( type.toLowerCase().equals( "double" ) 
                || type.toLowerCase().startsWith( "float" )
                || type.toLowerCase().startsWith( "real" ) ) {
      type = "Double";
    } else if ( type.toLowerCase().equals( "boolean" )
                || type.toLowerCase().equals( "bool" ) ) {
      type = "Boolean";
    } else if ( type.equals( "string" ) ) {
      type = "String";
    }
    
    return type;
  }

  public static String typeToParameterType( String type ) {
    if ( Utils.isNullOrEmpty( type ) ) {
      //type = "null";
    } else if ( type.toLowerCase().equals( "time" ) ) {
      type = "Timepoint";
    } else if ( type.toLowerCase().equals( "duration" ) ) {
      type = "Duration";
    } else {
      String classType = typeToClass( type );
      final String[] primClassesSame =
          new String[] { "Boolean", //"Character",
                         //"Byte", "Short", 
                         "Integer",
                         //"Long", "Float",
                         "Double",// "Void"
                         "String" };
      if ( Arrays.asList( primClassesSame ).contains( classType ) ) {
        type = classType;
      } else {
      //if ( classType.equals( type ) ) {
        type = "";
      }
      type = type + "Parameter";
    }
    return type;
  }

  public static String classToPrimitive( String type ) {
    
    if ( Utils.isNullOrEmpty( type ) ) {
      return type;
    }
    int pos = type.lastIndexOf( "Parameter" );
    if ( pos >= 0 ) {
      type = type.substring( 0, pos );
    }
    Class<?> c = null;
    try {
      c = Class.forName( type );
    } catch ( ClassNotFoundException e ) {
    }
    if ( c != null ) {
      if ( c.isPrimitive() ) {
        return c.getSimpleName().replace( '$', '.' );
      }
      type = c.getSimpleName().replace( '$', '.' );
    }
    final String[] primClassesSame =
        new String[] { "Boolean", //"Character",
                       "Byte", "Short", //"Integer",
                       "Long", "Float", "Double", "Void" };
    if ( Arrays.asList( primClassesSame ).contains( type ) ) {
      return type.toLowerCase();
    }
    if ( type.toLowerCase().equals( "integer" ) 
         || type.toLowerCase().equals( "time" )
         || type.toLowerCase().equals( "timepoint" ) 
         || type.toLowerCase().equals( "duration" ) ) { 
      return "int";
    }
    if ( type.toLowerCase().equals( "real" ) ) {
      return "double";
    }
    if ( type.toLowerCase().equals( "character" ) ) {
      return "char";
    }
    return type;
  }

  public String javaToAeExpr( String exprString, String type, 
                              boolean convertFcnCallArgsToExprs ) {
    return javaToAeExpr( exprString, type, convertFcnCallArgsToExprs, false );
  }
  public String javaToAeExpr( String exprString, String type, 
                              boolean convertFcnCallArgsToExprs,
                              boolean evaluateCall ) {
    Expression expr = parseExpression( exprString );
    return astToAeExpr( expr, type, convertFcnCallArgsToExprs, true, true,
                        evaluateCall );
  }

  public Expression parseExpression( String exprString ) {
    if ( Debug.isOn() ) Debug.outln( "trying to parse Java expression \"" + exprString + "\"" );
    ASTParser parser = new ASTParser( new StringReader( exprString ) );
    Expression expr = null;
    try {
      expr = parser.Expression();
    } catch ( ParseException e ) {
      e.printStackTrace();
    }
    return expr;
  }

  public FieldDeclaration createConstraintField( String name,
                                                 String expression,
                                                 String applicableStartTime,
                                                 String applicableEndTime ) {
    // ClassOrInterfaceType fieldType =
    // new ClassOrInterfaceType( "ConstraintExpression" );
    // FieldDeclaration f = null;
    if ( name == null ) {
      name = new String( "constraint" + counter++ );
    }
    String constructorArgs = null;
    String constraintType = null;
    if ( applicableStartTime == null || applicableEndTime == null
         || applicableStartTime.isEmpty() || applicableEndTime.isEmpty() ) {
      // constructorArgs = "new Expression<Boolean>( \"" + expression +
      // "\", \"Java\" )";
      constructorArgs = javaToAeExpr( expression, "Boolean", false );
      constraintType = "ConstraintExpression";
    } else {
      constructorArgs = javaToAeExpr( expression, "Boolean", false )
      // "new Expression<Boolean>( \""
      // + expression + "\", \"Java\" ), "
                        + applicableStartTime + ", " + applicableEndTime;
      constraintType = "TimeDependentConstraintExpression";
    }

    return createFieldOfGenericType( name, constraintType, constructorArgs );
    /*
     * 
     * 
     * VariableDeclaratorId id = new VariableDeclaratorId( name );
     * 
     * Expression init = null; if ( applicableStartTime == null ||
     * applicableStartTime == null ) { init = new NameExpr(
     * "new ConstraintExpression( new Expression<Boolean>( \"" + expression +
     * "\", \"Java\" ) )" ); } else { init = new NameExpr(
     * "new TimeDependentConstraintExpression( new Expression<Boolean>( \"" +
     * expression + "\", \"Java\" ), " + applicableStartTime + ", " +
     * applicableEndTime + " )" ); } VariableDeclarator variable = new
     * VariableDeclarator( id, init ); f = ASTHelper.createFieldDeclaration(
     * ModifierSet.PUBLIC, fieldType, variable ); return f;
     */
  }

  public FieldDeclaration createConstraintField( String name,
                                                 String expression,
                                                 String applicableStartTime,
                                                 String applicableEndTime,
                                                 MethodDeclaration initMembers ) {
    if ( initMembers == null ) {
      return createConstraintField( name, expression, applicableStartTime,
                                    applicableEndTime );
    }
    if ( name == null ) {
      name = new String( "constraint" + counter++ );
    }
    String constructorArgs = null;
    String constraintType = null;
    if ( applicableStartTime == null || applicableEndTime == null
         || applicableStartTime.isEmpty() || applicableEndTime.isEmpty() ) {
      constructorArgs = javaToAeExpr( expression, "Boolean", true );
      // constructorArgs = "new Expression<Boolean>( \"" + expression +
      // "\", \"Java\" )";
      constraintType = "ConstraintExpression";
    } else {
      constructorArgs = javaToAeExpr( expression, "Boolean", true )
      // constructorArgs = "new Expression<Boolean>( \""
      // + expression + "\", \"Java\" ), "
                        + applicableStartTime + ", " + applicableEndTime;
      constraintType = "TimeDependentConstraintExpression";
    }

    Statement s =
        createAssignmentOfGenericType( name, constraintType, null,
                                       constructorArgs );
    ASTHelper.addStmt( initMembers.getBody(), s );

    return createFieldOfGenericType( name, constraintType, null, null );
  }

  public FieldDeclaration createDependencyField( Param p,
                                                 MethodDeclaration initDependencies ) {
    assert( initDependencies != null );

    String suffix = "Dependency";
    String depName = p.name.replace( '.', '_' ) + suffix;
    Expression sinkExpr = parseExpression( p.name );
    String sink = null;
    if ( sinkExpr instanceof FieldAccessExpr ) {
      sink = fieldExprToAe( (FieldAccessExpr)sinkExpr, true, true, false, false,
                            false, true );
    } else {
      sink = p.name;
    }
    String source = javaToAeExpr( p.value, p.type, true, false );
    
    // Check and see if this dependency is on a parameter of another class.
    // If so, remove any default dependency on the same parameter.
    String scope = null; //, member = null;
    int pos = sink.lastIndexOf( "." );
    if ( pos >= 0 ) {
      scope = sink.substring( 0, sink.lastIndexOf( '.' ) );
////      String scopeParamString = (scope + ";").replaceAll("[.]getValue\\([^)]*\\);", ";");
////      scopeParamString = scopeParamString.substring( 0, scopeParamString.length()-1 );
//      member = sink.substring( sink.lastIndexOf( '.' ) + 1 );
//      final String[] defaults = new String[] {"startTime", "endTime", "duration"};
//      if ( Arrays.asList( defaults ).contains( member ) ) {
//        String removeDepStmt = scope + ".removeDependenciesForParameter(" + sink + ");";
//        addStatements( initDependencies.getBody(), removeDepStmt );
//      }
////      String scopeDepName = "scope_" + depName;
////      String applyDepStmts = "Dependency<?> " + scopeDepName + "= getDependency("
////                             + scopeParamString + ");\n  if (" + scopeDepName + " != null) " + scopeDepName + ".apply();"; 
////      addStatements( initDependencies.getBody(), applyDepStmts );
    }

    String addDepStmt = "addDependency( " + sink + ", " + source + " );";
    if ( scope != null ) {
      addDepStmt = scope + "." + addDepStmt;
    }
//    String constructorArgs = sink + ", " + source;
//    Statement s =
//        createAssignmentOfGenericType( depName, "Dependency", p.type,
//                                       constructorArgs );
//    ASTHelper.addStmt( initDependencies.getBody(), s );
    addStatements( initDependencies.getBody(), addDepStmt );
    return createFieldOfGenericType( depName, "Dependency", p.type, null );
  }

  public List< Pair< String, FieldDeclaration > > createEffectField( Node effectNode,
                                                                     MethodDeclaration initMembers ) {
    if ( effectNode == null ) return null;
    ClassOrInterfaceType fieldType =
        new ClassOrInterfaceType( "Effect" );
    ClassOrInterfaceType varFieldType =
            new ClassOrInterfaceType( "Parameter" ); 
    FieldDeclaration f = null;
    String effectText = fixValue( effectNode.getTextContent() );

    // parse the effect text as a MethodCallExpr
    if ( Debug.isOn() ) Debug.outln( "trying to parse effect as Java expression\"" + effectText
                        + "\"" );
    Expression expr = parseExpression( effectText );

    // If a method call, break it down into its parts.
    // String exprClassName = expr.getClass().getSimpleName();
    if ( expr instanceof MethodCallExpr ||
         expr instanceof ObjectCreationExpr ) {
//      MethodCallExpr mcExpr = (MethodCallExpr)expr;

      JavaForFunctionCall jffc = new JavaForFunctionCall( this, expr, false,
                                                          packageName );

      int myNum = counter++;
      String effectName = "effect" + myNum;
      String timeVaryingName = effectName + "Var";

      StringBuffer stmtString = new StringBuffer();
      // REVIEW -- might need a dependency instead of an assignment,
      //   timeVarying <-- new Expresion(timeVaryingV) 
      
      stmtString.append( "Object " + timeVaryingName + "V = " + jffc.object
                         + ";\n" );
      stmtString.append( timeVaryingName + " = new Parameter(\""
                         + timeVaryingName + "\", null, null, this);\n" );
      stmtString.append( "addDependency(" + timeVaryingName
                         + ", new Expression(" + timeVaryingName + "V));\n" );
      stmtString.append( effectName
                         + " = new EffectFunction( "
                         + jffc.toNewFunctionCallString()
                               .replaceAll( "([^A-Za-z0-9_])" + jffc.object.replaceAll( "[\\]\\[{}()*+?^$#-]", "." )
                                                + "([^A-Za-z0-9_])",
                                            "$1" + timeVaryingName + "$2" )
                         + " );" );
      
      addStatements( initMembers.getBody(), stmtString.toString() );

      VariableDeclaratorId id = new VariableDeclaratorId( effectName );
      Expression init = new NameExpr( "null" );
      VariableDeclarator variable = new VariableDeclarator( id, init );
      f =
          ASTHelper.createFieldDeclaration( ModifierSet.PUBLIC, fieldType,
                                            variable );
      List< Pair< String, FieldDeclaration > > pairs = new ArrayList< Pair< String, FieldDeclaration > >();
      pairs.add( new Pair< String, FieldDeclaration >( timeVaryingName, f ) );
      id = new VariableDeclaratorId( timeVaryingName );
      //init = new NameExpr( "null" );
      variable = new VariableDeclarator( id, init );
      f =
          ASTHelper.createFieldDeclaration( ModifierSet.PUBLIC, varFieldType,
                                            variable );
      pairs.add( new Pair< String, FieldDeclaration >( timeVaryingName, f ) );
      return pairs; //new Pair< String, FieldDeclaration >( timeVaryingName, f1 );
    } else {
      assert false; // TODO -- REVIEW -- Can it be something else? an
                    // assignment? signal = flow.receive(t)
    }
    return null; //new Pair( scopeExpr.toString(), f );
  }

  public FieldDeclaration
      createElaborationField( String name, String enclosingInstance,
                              String eventType,
                              String eventName, List< Param > arguments,
                              String conditionExpression,
                              String applicableStartTime,
                              String applicableEndTime,
                              MethodDeclaration initMembers ) {
    ClassOrInterfaceType fieldType =
        new ClassOrInterfaceType( "ElaborationRule" );
    FieldDeclaration f = null;
    int myNum = counter++;
    if ( name == null ) {
      name = new String( "elaborationRule" + myNum );
    }

    String conditionName = "condition" + myNum;
    String argumentsName = "arguments" + myNum;

    StringBuffer stmtsString = new StringBuffer();
    stmtsString.append( "Expression<?>[] " + argumentsName
                        + " = new Expression<?>[" + arguments.size() + "];\n" );
    // TODO -- Need to make sure this constructor exists!
    // TODO -- Should we have a cleanup after reading XML to tie/check
    // references or wait for javac to fail?
    for ( int i = 0; i < arguments.size(); ++i ) {
      Param p = arguments.get( i );

//      String parameterTypeAndArgs[] =
//          convertToEventParameterTypeAndConstructorArgs( p, eventType );
      String type = typeToClass( p.type );
      if ( Utils.isNullOrEmpty( type ) ) {
        Param param = lookupMemberByName( eventType, p.name, true, false );
        if ( param != null ) {
          type = param.type;
        }
      }
      if ( Utils.isNullOrEmpty( type ) ) {
        System.err.println( "Error! cannot create elaboration invocation "
                            + "argument for " + p );
        type = null;
      } else {
        stmtsString.append( argumentsName + "[" + i + "] = "
                            + javaToAeExpr( p.value, type, 
                                                     true ) + ";\n" );
      }
    }

    stmtsString.append( "Expression< Boolean > "
                        + conditionName
                        + " = "
                        + javaToAeExpr( conditionExpression, "Boolean", 
                                                 true )
                        + ";\n" );

//    boolean needToMakeAParameter = false;
////    if ( !enclosingInstance.equals( "this" ) ) {
////      Param p = lookupMemberByName( currentClass, enclosingInstance );
////      if ( p == null ) {
////        System.err.println( "Couldn't find member " + enclosingInstance + " in "
////                            + currentClass );
////      } else {
////        enclosingInstance += ".getValue(true)";
////      }
////    }

//    if ( enclosingInstance.equals( "this" ) ) {
//      needToMakeAParameter = true;
//    } else {
//      Param p = null;
//      if ( !Utils.isNullOrEmpty( enclosingInstance ) ) { 
//        lookupCurrentClassMember( enclosingInstance );
//      }
//      if ( p == null ) {
//        needToMakeAParameter = true;
//      }
//    }
//    if ( needToMakeAParameter ) {
//      enclosingInstance =
//          //"new Parameter<" + currentClass + ">( \"" + currentClass
//          "new Parameter( \"" + currentClass
//              + "\", null, " + enclosingInstance + ", null )";
//    }
    stmtsString.append( name + " = addElaborationRule( " + conditionName + ", "
                        + enclosingInstance + ", "
                        + ClassUtils.noParameterName( getClassNameWithScope( eventType ) )
                        + ".class, "
                        + ( Utils.isNullOrEmpty( eventName ) ? "null" : "\"" 
                            + eventName + "\"" )
                        + ", " + argumentsName + " );\n" );

    addStatements( initMembers.getBody(), stmtsString.toString() );

    VariableDeclaratorId id = new VariableDeclaratorId( name );
    Expression init = new NameExpr( "null" );
    VariableDeclarator variable = new VariableDeclarator( id, init );
    f =
        ASTHelper.createFieldDeclaration( ModifierSet.PUBLIC, fieldType,
                                          variable );
    return f;
  }

  
  
  
  // REVIEW -- Do we really need to create fields for these? Just need
  // to load parameters from constructor; and not redefine loadPrameters().
  public ArrayList< FieldDeclaration >
      getParameters( Node node, MethodDeclaration initMembers ) {
    ArrayList< FieldDeclaration > parameters =
        new ArrayList< FieldDeclaration >();
    if ( node != null ) {
      List< Node > nodeList = XmlUtils.getChildNodes( node, "parameter" );
      // NodeList nodeList = node.XmlUtils.getChildNodes();
      for ( int i = 0; i < nodeList.size(); i++ ) {
        Node childNode = nodeList.get( i );
        Param p = new Param( childNode );
        FieldDeclaration f = createParameterField( p, initMembers );
        if ( f != null ) {
          parameters.add( f );
        }
      }
    }
    return parameters;
  }

  // REVIEW -- Do we really need to create a field for these?
  public Collection< FieldDeclaration >
      getConstraints( Node node, MethodDeclaration initMembers ) {
    ArrayList< FieldDeclaration > constraints =
        new ArrayList< FieldDeclaration >();
    List< Node > nodeList = XmlUtils.getChildNodes( node, "constraint" );
    for ( int i = 0; i < nodeList.size(); i++ ) {
      Node childNode = nodeList.get( i );
      String name = fixName( XmlUtils.getChildElementText( childNode, "name" ) );
      String expression = fixValue( XmlUtils.getChildElementText( childNode, "expression" ) );
      Node timePeriodNode = XmlUtils.getChildNode( childNode, "timePeriod" );
      String applicableStartTime =
          XmlUtils.getChildElementText( timePeriodNode, "start" );
      String applicableEndTime = XmlUtils.getChildElementText( timePeriodNode, "end" );
      FieldDeclaration f =
          createConstraintField( name, expression, applicableStartTime,
                                 applicableEndTime, initMembers );
      if ( f != null ) {
        constraints.add( f );
      }
    }
    return constraints;
  }

  public Collection< FieldDeclaration >
      getDependencies( Node node, MethodDeclaration initDependencies ) {
    ArrayList< FieldDeclaration > dependencies =
        new ArrayList< FieldDeclaration >();
    List< Node > nodeList = XmlUtils.getChildNodes( node, "dependency" );
    for ( int i = 0; i < nodeList.size(); i++ ) {
      Node childNode = nodeList.get( i );
      if ( childNode == null ) continue;
      Param depParam = new Param( childNode );
      if ( Utils.isNullOrEmpty( depParam.name ) ) continue;
      if ( Utils.isNullOrEmpty( depParam.value ) ) continue;
      if ( depParam.name.equals( "startTime" ) ) {
        gotStartTimeDependency = true;
      } else  if ( depParam.name.equals( "endTime" ) ) {
        gotEndTimeDependency = true;
      } else if ( depParam.name.equals( "duration" ) ) {
        gotDurationDependency = true;
      }
      FieldDeclaration f =
          createDependencyField( depParam, initDependencies );
      if ( f != null ) {
        dependencies.add( f );
      }
    }
    return dependencies;
  }

  private Collection< Pair< String, FieldDeclaration > >
      getEffects( Node effectsNode, MethodDeclaration initMembers ) {
    ArrayList< Pair< String, FieldDeclaration > > effects =
        new ArrayList< Pair< String, FieldDeclaration > >();
    List< Node > nodeList = XmlUtils.getChildNodes( effectsNode, "effect" );
    for ( int i = 0; i < nodeList.size(); i++ ) {
      Node childNode = nodeList.get( i );
      List< Pair< String, FieldDeclaration > > pairs =
          createEffectField( childNode, initMembers );
      if ( pairs != null ) {
        effects.addAll( pairs );
      }
    }
    return effects;
  }

  public Collection< FieldDeclaration >
      getElaborations( Node node, MethodDeclaration initMembers ) {
    ArrayList< FieldDeclaration > elaborations =
        new ArrayList< FieldDeclaration >();
    List< Node > nodeList = XmlUtils.getChildNodes( node, "elaboration" );
    for ( int i = 0; i < nodeList.size(); i++ ) {
      Node elaborationNode = nodeList.get( i );
      if ( elaborationNode == null ) continue;
      Node invocationNode = XmlUtils.getChildNode( elaborationNode, "eventInvocation" );
      Node conditionNode = XmlUtils.getChildNode( elaborationNode, "condition" );
      if ( invocationNode != null ) {
        String enclosingInstance = fixName( XmlUtils.getChildElementText( invocationNode, "enclosingInstance" ) );
        if ( enclosingInstance == null ) enclosingInstance = "null";
        String eventType = fixName( XmlUtils.getChildElementText( invocationNode, "eventType" ) );
        String eventName = fixName( XmlUtils.getChildElementText( invocationNode, "eventName" ) );
        Node argumentsNode = XmlUtils.getChildNode( invocationNode, "arguments" );
        List< Param > arguments = new ArrayList< Param >();
        if ( argumentsNode != null ) {
          List< Node > argNodeList = XmlUtils.getChildNodes( argumentsNode, "parameter" );
          for ( int j = 0; j < argNodeList.size(); j++ ) {
            Node argNode = argNodeList.get( j );
            arguments.add( new Param( argNode ) );
          }
        }
        String expression = "true"; // default
        String applicableStartTime = "-1"; // default
        String applicableEndTime = "-1"; // default
        if ( conditionNode != null ) {
          expression = fixValue( XmlUtils.getChildElementText( conditionNode, "expression" ) );
          Node timePeriodNode = XmlUtils.getChildNode( conditionNode, "timePeriod" );
          if ( timePeriodNode != null ) {
            applicableStartTime = XmlUtils.getChildElementText( timePeriodNode, "start" );
            applicableEndTime = XmlUtils.getChildElementText( timePeriodNode, "end" );
          }
        }
        FieldDeclaration f =
            createElaborationField( null, enclosingInstance, eventType,
                                    eventName, arguments,
                                    expression, applicableStartTime,
                                    applicableEndTime, initMembers );
        if ( f != null ) {
          elaborations.add( f );
        }
      }
    }
    return elaborations;
  }

  protected void
    buildMethodTable( Document doc,
                      Map< String, Map< String, Set< MethodDeclaration > > > methodTable ) {
    List< Node > classNodes = XmlUtils.findNodes( doc, "class" );
    for ( Node classNode : classNodes ) {
      String className = getClassName( classNode );
      
      Map< String, Set< MethodDeclaration > > classMethods = 
          methodTable.get( className );

      if ( classMethods == null ) {
        classMethods = new TreeMap< String, Set< MethodDeclaration > >();
        methodTable.put( className, classMethods );
      }
      Collection< MethodDeclaration > methodCollection = 
          getMethods( classNode );

      for ( MethodDeclaration methodDecl : methodCollection ) {
        Set< MethodDeclaration > methodSet =
            classMethods.get( methodDecl.getName() );
        if ( methodSet == null ) {
          methodSet = new TreeSet<MethodDeclaration>(new CompareUtils.GenericComparator< MethodDeclaration >());
          classMethods.put( methodDecl.getName(), methodSet );
        }
        methodSet.add( methodDecl );
      }
    }
  }
  
  public static final Set< MethodDeclaration > emptyMethodDeclarationSet =
      new TreeSet<MethodDeclaration>(new CompareUtils.GenericComparator< MethodDeclaration >());

  public Set< MethodDeclaration > getMethodsForClass( String className ) {
    Map< String, Set< MethodDeclaration > > classMethods =
        methodTable.get( className );
    if ( classMethods == null ) return emptyMethodDeclarationSet;
    Set< MethodDeclaration > methodsForClass = new TreeSet<MethodDeclaration>(new CompareUtils.GenericComparator< MethodDeclaration >());
    for ( Set< MethodDeclaration > methodsByName : classMethods.values() ) {
      methodsForClass.addAll( methodsByName );
    }
    return methodsForClass;
  }

  public Set< MethodDeclaration > getMethodsWithName( String methodName ) {
    return getClassMethodsWithName( methodName, currentClass );
  }

  /**
   * @param className
   * @return constructors for the class with the given className that is defined
   *         in the XML.
   */
  public Set< ConstructorDeclaration > getConstructors( String className ) {
    ClassOrInterfaceDeclaration classDecl = getClassDeclaration( className );
    if ( classDecl == null ) return null;
    if ( classDecl.getMembers() == null ) return null;
    Set< ConstructorDeclaration > s = new TreeSet<ConstructorDeclaration>(new CompareUtils.GenericComparator< ConstructorDeclaration >());
    for ( BodyDeclaration m : classDecl.getMembers() ) {
      if ( m instanceof ConstructorDeclaration ) {
        s.add( (ConstructorDeclaration)m );
      }
    }
    return s;
  }

  
  /**
   * Look for a class declaration of a particular name nested inside another class declaration.
   * @param className
   * @param classDecl
   * @return
   */
  public ClassOrInterfaceDeclaration getClassDeclaration( String className,
                                                          ClassOrInterfaceDeclaration classDecl ) {
    // First check and see if this is "the one."
    if ( classDecl.getName().equals( className ) ) {
      return classDecl;
    } else {
      // Now check nested classes.
      if ( classDecl != null && classDecl.getMembers() != null ) {
        for ( BodyDeclaration bd : classDecl.getMembers() ) {
          if ( bd instanceof ClassOrInterfaceDeclaration ) {
            ClassOrInterfaceDeclaration nestedClassDecl = (ClassOrInterfaceDeclaration)bd;
            nestedClassDecl = getClassDeclaration( className, nestedClassDecl );
            if ( nestedClassDecl != null ) return nestedClassDecl;
          }
        }
      }
    }
    return null;
  }
  
  public ClassOrInterfaceDeclaration getClassDeclaration( String className,
                                                          CompilationUnit cu ) {
    if ( cu == null || cu.getTypes() == null ) return null;
    for ( TypeDeclaration t : cu.getTypes() ) {
      if ( t instanceof ClassOrInterfaceDeclaration ) {
        ClassOrInterfaceDeclaration classDecl = 
            getClassDeclaration( className, (ClassOrInterfaceDeclaration)t );
        if ( classDecl != null ) return classDecl;
      }
    }
    return null;
  }
  
  public ClassOrInterfaceDeclaration getClassDeclaration( String className ) {
    className = ClassUtils.simpleName( className );
    ClassOrInterfaceDeclaration classDecl = null;
    CompilationUnit cu = classes.get( className );
    if ( cu == null ) {
      // See if enclosing class declaration has this one's.
      String parentClassName = getEnclosingClassName( className );
      if ( !Utils.isNullOrEmpty( parentClassName ) ) {
        ClassOrInterfaceDeclaration parentDecl = getClassDeclaration( parentClassName );
        if ( parentDecl != null && parentDecl.getMembers() != null ) {
          classDecl = getClassDeclaration( className, parentDecl );
          return classDecl;
        }
      }
      return null;
    }
    classDecl = getClassDeclaration( className, cu );
    if ( classDecl == null ) {
      for ( CompilationUnit cu2 : classes.values() ) {
        if ( cu == cu2 ) continue;
        classDecl = getClassDeclaration( className, cu );
        if ( classDecl != null ) {
          return classDecl;
        }
      }
    }
    return classDecl;
  }

  public Set< MethodDeclaration > getClassMethodsWithName( String methodName,
                                                           String className ) {
    if ( Debug.errorOnNull( false,
                            "Passed null to getClassMethodsWithName( methodName="
                                + methodName + ", className=" + className + ")",
                            methodName, className ) ) {
      return emptyMethodDeclarationSet;
    }
    
    Map< String, Set< MethodDeclaration > > classMethods =
        methodTable.get( className );
    if ( classMethods == null ) {
      classMethods = methodTable.get( ClassUtils.simpleName( className ) );
    }
    if ( classMethods == null ) {
      String scopedName = getClassNameWithScope( className );
      if ( !Utils.isNullOrEmpty( scopedName ) ) {
        classMethods = methodTable.get( scopedName );
      }
    }
    Set< MethodDeclaration > methodSet = emptyMethodDeclarationSet;
    if ( classMethods == null && isInnerClass( className ) ) {
      methodSet = getClassMethodsWithName( methodName, getEnclosingClassName( className ) );
    }
    if ( Utils.isNullOrEmpty( methodSet ) && classMethods == null ) {
      if ( Debug.isOn() ) Debug.outln( "getClassMethodsWithName(" + methodName + ", " + className
                   + ") couldn't find class!\nmethodTable="
                   + methodTable.toString() );
      return methodSet;
    }
    if ( Utils.isNullOrEmpty( methodSet ) ) {
      methodSet = classMethods.get( methodName );
    }
    if ( Utils.isNullOrEmpty( methodSet ) ) {
      if ( Debug.isOn() ) Debug.outln( "getClassMethodsWithName(" + methodName + ", " + className
                   + ") = null\nmethodTable=" + methodTable.toString() );
    }
    return methodSet;
  }

  public static MethodDeclaration parseMethodDeclaration( String methodText ) {
    if ( Debug.isOn() ) Debug.outln( "About to parse \"" + methodText + "\"");
    ASTParser parser = new ASTParser( new StringReader( methodText ) );
    try {
      BodyDeclaration md = parser.ClassOrInterfaceBodyDeclaration( false );
      return (MethodDeclaration)md;
    } catch ( ClassCastException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (ParseException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
    return null;
  }
  
  public static ConstructorDeclaration parseConstructorDeclaration( String ctorText ) {
    if ( Debug.isOn() ) Debug.outln( "About to parse \"" + ctorText + "\"");
    ASTParser parser = new ASTParser( new StringReader( ctorText ) );
    try {
      BodyDeclaration cd = parser.ClassOrInterfaceBodyDeclaration( false );
      return (ConstructorDeclaration)cd;
    } catch ( ClassCastException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (ParseException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
    return null;
  }
  
  // TODO -- If non-static and members are referenced inside, we will need to go
  // in and call param.get/setValue(). Need to do this inside expressions
  // elsewhere (javaToEventExpression()) , too.
  public Collection< MethodDeclaration > getMethods( Node classNode ) {
    ArrayList< MethodDeclaration > methodDeclarations =
        new ArrayList< MethodDeclaration >();
    Node methodsNode = XmlUtils.getChildNode( classNode, "methods" );
    List< Node > mNodeList = XmlUtils.getChildNodes( methodsNode, "function" );
    for ( Node mNode : mNodeList ) {
      String methodString = fixValue( mNode.getTextContent() );
      MethodDeclaration methodDecl = parseMethodDeclaration( methodString );
      if ( methodDecl != null ) {
        if ( !ModifierSet.isPrivate( methodDecl.getModifiers() )
             && !ModifierSet.isProtected( methodDecl.getModifiers() ) ) {
          methodDecl.setModifiers( ModifierSet.addModifier( methodDecl.getModifiers(),
                                                            ModifierSet.PUBLIC ) );
          if ( isClassStatic( getClassName( classNode ) ) ) {
            makeStatic(methodDecl);
            methodDecl.setModifiers( ModifierSet.addModifier( methodDecl.getModifiers(),
                                                              ModifierSet.STATIC ) );
          }
        }
        methodDeclarations.add( methodDecl );
      }
    }
    return methodDeclarations;
  }

  /**
   * @return the loader
   */
  public ClassLoader getLoader() {
    if ( loader == null ) {
      loader = getClass().getClassLoader();//fileManager.getClassLoader(null);
    }
    return loader;
  }

  /**
   * @param loader the loader to set
   */
  public void setLoader( ClassLoader loader ) {
    this.loader = loader;
  }

  /**
   * @return the packageName
   */
  public String getPackageName() {
    return packageName;
  }

  /**
   * @param packageName the packageName to set
   */
  public void setPackageName( String packageName ) {
    if ( Debug.isOn() ) Debug.outln( "setting package name to " + packageName );
    this.packageName = packageName;
  }

  public void writeJavaFile( String fileName ) throws IOException {
    File f = new File( fileName );
    FileWriter w = new FileWriter( f );
    w.write( currentCompilationUnit.toString() );
    w.close();
  }

  public void writeJavaFiles( String javaPath ) throws IOException {
    for ( Entry< String, CompilationUnit > e : classes.entrySet() ) {
      currentClass = e.getKey();
      currentCompilationUnit = e.getValue();
      String fileName =
          ( javaPath.trim() + File.separator + e.getKey() + ".java" );
      writeJavaFile( fileName );
      if ( Debug.isOn() ) Debug.outln( "wrote compilation unit to file " + fileName );
    }
  }

  public static List< String > getClassNamesFromJARFile( String jar,
                                                         String packageName ) {
    List< String > classNames = new ArrayList< String >();
    List< Class< ? > > classes = getClassesFromJARFile( jar, packageName );
    if ( !Utils.isNullOrEmpty( classes ) ) {
      for ( Class< ? > c : classes ) {
        classNames.add( c.getName().replace( '$', '.' ) );
      }
    }
    return classNames;
  }

  public static List< Class< ? > >
      getClassesFromJARFile( String jar, String packageName ) throws Error {
    final List<Class<?>> classes = new ArrayList<Class<?>>();
    JarInputStream jarFile = null;
    try {
      jarFile = new JarInputStream( new FileInputStream( jar ) );
      JarEntry jarEntry = null;
      do {
        try {
          jarEntry = jarFile.getNextJarEntry();
        } catch ( IOException ioe ) {
          System.err.println( "Unable to get next jar entry from jar file '"
                              + jar + "' -- " +  ioe );
        }
        if ( jarEntry != null ) {
          extractClassFromJar( jar, packageName, classes, jarEntry );
        }
      } while ( jarEntry != null );
      closeJarFile( jarFile );
    } catch ( IOException ioe ) {
      System.err.println( "Unable to get Jar input stream from '" + jar
                          + "' -- " + ioe );
    } finally {
      closeJarFile( jarFile );
    }
    return classes;
  }

  private static void
      extractClassFromJar( final String jar, final String packageName,
                           final List<Class<?>> classes, JarEntry jarEntry ) throws Error {
    String className = jarEntry.getName();
    if ( className.endsWith( ".class" ) ) {
      className =
          className.substring( 0, className.length() - ".class".length() );
      if ( className.startsWith( packageName ) ) {
        try {
          classes.add( Class.forName( className.replace( '/', '.' ) ) );
        } catch ( ClassNotFoundException cnfe ) {
          System.err.println( "unable to find class named "
                              + className.replace( '/', '.' )
                              + "' within jar '" + jar + "' -- " + cnfe );
        }
      }
    }
  }

  private static void closeJarFile( final JarInputStream jarFile ) {
    if ( jarFile != null ) {
      try {
        jarFile.close();
      } catch ( IOException ioe ) {
        System.err.println( "Failed to close Jar input stream '" + jarFile
                            + "' -- " + ioe );
      }
    }
  }

  public static boolean deleteFiles( File[] files ) {
    if ( files == null ) return false;
    boolean succ = true;
    for ( File f : files ) {
      if ( !f.delete() ) {
        succ = false;
      }
    }
    return succ;
  }
  
  public static File[] getJavaFileList( File path ) {
    File[] fileArr = null;
    assert path.exists();
    fileArr = path.listFiles();
    return fileArr;
  }
  
  public static File[] getJavaFileList( String javaPath ) {
    File[] fileArr = null;
    File path = new File(javaPath);
    return getJavaFileList( path );
  }
  
  public File[] getJavaFiles( String javaPath, boolean sourceOrClass,
                              boolean justCurrentClasses ) {
    File[] fileArr = null;
    File path = new File(javaPath);
    if ( javaPath == null ) {
      javaPath = (sourceOrClass ? "src" : "bin") + File.separator + this.packageName;
      File path2 = new File(javaPath);
      if ( !path2.exists() && !sourceOrClass ) {
        javaPath = "src" + File.separator + this.packageName;
        path2 = new File(javaPath);
      }
      if ( path2.exists() ) {
        path = path2;
      }
    }
    assert path.exists();
    if ( !justCurrentClasses ) {
      fileArr = getJavaFileList( path );
      if ( fileArr != null ) {
        List< File > files = new ArrayList< File >();
        for ( File f : fileArr ) {
          if ( f.getName().endsWith( sourceOrClass ? ".java" : ".class" ) ) {
            files.add( f );
          }
        }
        fileArr = new File[ files.size() ];
        int ctr = 0;
        for ( File f : files ) {
          fileArr[ ctr++ ] = f;
        }
      }
      return fileArr;
    }

    fileArr = new File[ this.classes.size() ];
    if ( !this.classes.isEmpty() ) {
      int ctr = 0;
      for ( String clsName : this.classes.keySet() ) {
        String filePathName =
            javaPath.trim() + File.separator + clsName
                + ( sourceOrClass ? ".java" : ".class" );
        fileArr[ ctr++ ] = new File( filePathName );
      }
    }
    return fileArr;
  }
  
  public boolean compileJavaFiles( String javaPath ) {
    File[] fileArr = getJavaFiles( javaPath, true, true );//path.listFiles();
    if ( fileArr.length == 0 ) fileArr = getJavaFiles( javaPath, true, false );
    System.out.println( "java.home = " + System.getProperty( "java.home" ) );
    //System.setProperty( "java.home", "C:\\Program Files\\Java\\jdk1.6.0_35");
    //System.out.println( "java.home = " + System.getProperty( "java.home" ) );
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    System.out.println( "compileJavaFiles(" + javaPath
                        + "): about to get compilationUnits/java file objects for: "
                        + Utils.toString(fileArr) );
    if ( getFileManager() == null ) {
      System.err.println( "No StandardJavaFileManager to compile Java classes." );
      return false;
    }
    Iterable<? extends JavaFileObject> compilationUnits =
        fileManager.getJavaFileObjectsFromFiles(Arrays.asList(fileArr));
    System.out.println( "compileJavaFiles(" + javaPath
                        + "): got compilationUnits as java file objects: "
                        + compilationUnits );
    CompilationTask task = 
        compiler.getTask(null, fileManager, null, null, null, compilationUnits);
    System.out.println( "compileJavaFiles(" + javaPath + 
                        "): got CompilationTask: " + task );
    boolean succ = task.call();
    System.out.println( "compileJavaFiles(" + javaPath + 
                        "): compilation success=" + succ );
    return succ;
  }
  
  public boolean loadClasses( String javaPath, String packageName ) {
    boolean succ = true;
    File path = new File(javaPath);
    assert path.exists();
    File[] fileArr = null;
    fileArr = getJavaFiles( javaPath, false, true );//path.listFiles();
    if ( fileArr.length == 0 ) fileArr = getJavaFiles( javaPath, false, false );
    //loader = getClass().getClassLoader();//fileManager.getClassLoader(null);
    for ( File f : fileArr ) {
      int pos = f.getName().lastIndexOf( '.' );
      if ( pos == -1 ) pos = f.getName().length();
      String className = packageName + '.' + f.getName().substring( 0, pos );
      try {
        Class<?> cls = getLoader().loadClass( className );
        System.out.println( "loadClasses(" + javaPath + ", " + packageName +
                            "): loaded class: " + cls.getName() );
        try {
          final Object[] a = new Object[]{};
          if ( cls != null
               && ( cls.getName().equals( packageName + ".Main" )
                    || ( mainClass == null
                         && cls.getMethod( "main", a.getClass() ) != null ) ) ) {
            mainClass = cls;
          }
        } catch ( SecurityException e ) {
        } catch ( NoSuchMethodException e ) {
        }
      } catch ( ClassNotFoundException e ) {
        System.err.println( "Couldn't load class: " + className );
        e.printStackTrace();
        succ = false;
      }
    }
    return succ;
  }

  
  public String getPackageSourcePath( String projectPath ) {
    if ( projectPath == null ) {
      projectPath = "";
    } else {
      projectPath += File.separator;
    }
    String packagePath = getPackageName().replace( '.', File.separatorChar );
    String srcPath = projectPath + "src" + File.separator + packagePath;
    return srcPath;
  }
  
  public String getPackageBinPath( String projectPath ) {
    if ( projectPath == null ) {
      projectPath = "";
    } else {
      projectPath += File.separator;
    }
    String packagePath = getPackageName().replace( '.', File.separatorChar );
    String binPath = projectPath + "bin" + File.separator + packagePath;
    return binPath;
  }
  
  public boolean compileAndLoad( String projectPath ) {
    boolean succCompile = compile( projectPath );
    boolean succLoad = load( projectPath );
    return succCompile && succLoad;
  }

  public boolean compile( String projectPath ) {
    boolean succ = compileJavaFiles( getPackageSourcePath( projectPath ) );
    return succ;
  }
  
  public boolean load( String projectPath ) {
    boolean succLoad = loadClasses( getPackageBinPath( projectPath ),
                                    getPackageName() );
    return succLoad;
  }
  
  public boolean compileLoadAndRun( String projectPath ) {
    boolean succ = compileAndLoad( projectPath );
    if ( !succ ) return false;
    return runMain(); 
  }

  public Class<?> getMainClass() {
    if ( mainClass == null ) {
      try {
        mainClass = getLoader().loadClass( getPackageName() + ".Main" );
      } catch ( ClassNotFoundException e ) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      if ( mainClass != null ) {
        if ( Debug.isOn() ) Debug.outln( "loaded class: " + mainClass.getName() );
      }
    }
    return mainClass;
  }
  
  public DurativeEvent getMainInstance() {
    if ( mainInstance == null) {
      try {
        mainInstance = (DurativeEvent)getMainClass().newInstance();
      } catch ( InstantiationException e ) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch ( IllegalAccessException e ) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    return mainInstance;
  }
  
  
  /**
   * Invoke Main.main()
   * @return whether the invocation was successful.
   */
  public boolean runMain() {
    boolean succ = false;
//        //Class<?> cls = Utils.getClassForName( "Main", getPackageName(), true );
//        Class< ? > cls;
//        try {
//          cls = Class.forName( "Main" );
//        } catch ( ClassNotFoundException e1 ) {
//          cls = Utils.getClassForName( "Main", getPackageName(), true );
//          if ( cls == null ) {
//            System.err.println("Couldn't find main!");
//            e1.printStackTrace();
//            return false;
//          }
//        }
    String args[] = new String[] { null };
    Utils.loader = getLoader();
    Method m = ClassUtils.getMethodForArgTypes( getMainClass(), "main", args.getClass() );
    try {
      m.invoke( null, (Object[])args );
      succ = true;
    } catch ( IllegalAccessException e ) {
      e.printStackTrace();
    } catch ( IllegalArgumentException e ) {
      e.printStackTrace();
    } catch ( InvocationTargetException e ) {
      e.printStackTrace();
    }
    return succ;
  }

  public DurativeEvent generateExecution() {
    if ( Debug.isOn() ) Debug.outln( "generateExecution(): begin()" );
    DurativeEvent event = getMainInstance();
    if ( event == null ) {
      if ( Debug.isOn() ) Debug.errln( "generateExecution(): null main instance! no execution!" );
    } else {
      try {
        event.execute();
      } catch ( Exception e ) {
        if ( Debug.isOn() ) Debug.errln( e.toString() );
        e.printStackTrace();
      }
    }
    if ( Debug.isOn() ) Debug.outln( "generateExecution(): end()" );
    return event;
  }
  
  public <T extends DurativeEvent> T generateExecution( Class<T> eventToExecute ) {
    //boolean succ = true;
    T instance = null;
    try {
      instance = eventToExecute.newInstance();
      instance.execute();
      //succ = true;
    } catch ( InstantiationException e ) {
      e.printStackTrace();
    } catch ( IllegalAccessException e ) {
      e.printStackTrace();
    }
    
    return instance;
  }
  
  /**
   * @return the compilationUnit
   */
  public CompilationUnit getCurrentCompilationUnit() {
    return currentCompilationUnit;
  }

  /**
   * @return the currentClass
   */
  public String getCurrentClass() {
    return currentClass;
  }

  /**
   * @param currentClass
   *          the currentClass to set
   */
  public void setCurrentClass( String currentClass ) {
    this.currentClass = currentClass;
    if ( classes.containsKey( currentClass ) ) {
      currentCompilationUnit = classes.get( currentClass );
    }
  }
}

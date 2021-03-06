/**
 * 
 */
package gov.nasa.jpl.ae.event;

import java.lang.reflect.Method;
import java.util.Vector;

/**
 *
 */
public class Command extends FunctionCall {

  public static TimeVaryingList<Command> commandSequence = 
      new TimeVaryingList< Command >( "commandSequence" );
  
  /**
   * @param method
   */
  public Command( Method method ) {
    super( method );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param cls
   * @param methodName
   */
  public Command( Class< ? > cls, String methodName ) {
    super( cls, methodName );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   */
  public Command( Object object, Method method ) {
    super( object, method );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   */
  public Command( Object object, Class< ? > cls, String methodName ) {
    super( object, cls, methodName );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   * @param arguments
   */
  public Command( Object object, Method method, Vector< Object > arguments ) {
    super( object, method, arguments );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   * @param arguments
   */
  public Command( Object object, Class< ? > cls, String methodName,
                  Vector< Object > arguments ) {
    super( object, cls, methodName, arguments );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   * @param arguments
   * @param nestedCall
   */
  public Command( Object object, Method method, Vector< Object > arguments,
                  Call nestedCall ) {
    super( object, method, arguments, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   * @param arguments
   * @param nestedCall
   */
  public Command( Object object, Method method, Vector< Object > arguments,
                  Parameter< Call > nestedCall ) {
    super( object, method, arguments, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   * @param arguments
   * @param nestedCall
   */
  public Command( Object object, Class< ? > cls, String methodName,
                  Vector< Object > arguments, Call nestedCall ) {
    super( object, cls, methodName, arguments, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   * @param arguments
   * @param nestedCall
   */
  public Command( Object object, Class< ? > cls, String methodName,
                  Vector< Object > arguments, Parameter< Call > nestedCall ) {
    super( object, cls, methodName, arguments, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   * @param argumentsA
   */
  public Command( Object object, Method method, Object[] argumentsA ) {
    super( object, method, argumentsA );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   * @param argumentsA
   */
  public Command( Object object, Class< ? > cls, String methodName,
                  Object[] argumentsA ) {
    super( object, cls, methodName, argumentsA );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   * @param argumentsA
   * @param nestedCall
   */
  public Command( Object object, Method method, Object[] argumentsA,
                  Call nestedCall ) {
    super( object, method, argumentsA, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param method
   * @param argumentsA
   * @param nestedCall
   */
  public Command( Object object, Method method, Object[] argumentsA,
                  Parameter< Call > nestedCall ) {
    super( object, method, argumentsA, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   * @param argumentsA
   * @param nestedCall
   */
  public Command( Object object, Class< ? > cls, String methodName,
                  Object[] argumentsA, Call nestedCall ) {
    super( object, cls, methodName, argumentsA, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param object
   * @param cls
   * @param methodName
   * @param argumentsA
   * @param nestedCall
   */
  public Command( Object object, Class< ? > cls, String methodName,
                  Object[] argumentsA, Parameter< Call > nestedCall ) {
    super( object, cls, methodName, argumentsA, nestedCall );
    // TODO Auto-generated constructor stub
  }

  /**
   * @param e
   */
  public Command( FunctionCall e ) {
    super( e );
    // TODO Auto-generated constructor stub
  }

}

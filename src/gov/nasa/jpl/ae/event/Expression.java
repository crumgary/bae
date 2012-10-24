package gov.nasa.jpl.ae.event;
import gov.nasa.jpl.ae.solver.Constraint;
import gov.nasa.jpl.ae.solver.Domain;
import gov.nasa.jpl.ae.solver.HasConstraints;
import gov.nasa.jpl.ae.solver.HasDomain;
import gov.nasa.jpl.ae.solver.Satisfiable;
import gov.nasa.jpl.ae.solver.SingleValueDomain;
import gov.nasa.jpl.ae.util.Debug;
import gov.nasa.jpl.ae.util.Pair;
import gov.nasa.jpl.ae.util.Utils;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import junit.framework.Assert;

/**
 * 
 */

/**
 * @author bclement
 * 
 *         TODO -- REVIEW -- This is a little awkward. What about an Expression
 *         interface with implementations of Value, Parameter, MethodExpression,
 *         and Function? Would it make sense to put constructors in an
 *         expression factory?
 * 
 *         TODO -- Look at javaparser at http://code.google.com/p/javaparser/
 *         and JavaCC.
 * 
 *         TODO -- Consider using Ptolemy's expression parser.
 * 
 */
public class Expression< ResultType >
                                    implements HasParameters, Groundable,
                                               LazyUpdate, Satisfiable,
                                               HasDomain, HasTimeVaryingObjects {//, Comparable< Expression< ? > > {
	public Object expression = null;
  public Type type = Type.None;
	public Class<? extends ResultType> resultType = null;//Type.None;
  // freeParameters if not null specifies which parameters can be reassigned
  // values for satisfy().
  protected Set< Parameter< ? > > freeParameters = null;


	public enum Type {
		None(null), Value(Object.class),
		Parameter(Parameter.class),
		//Method(Method.class),
		Function(FunctionCall.class),
    Constructor(ConstructorCall.class);
		
		private Class<?> myClass;
		Type( Class<?> c ) {
			myClass = c;
		}
		public Class<?> getTypeClass() {
			return myClass;
		}
	}


  /**
   * @param value
   */
  public Expression( String expressionString, String expressionLanguage ) {
    // TODO -- javaparser?
    System.err.println("Error! Expression( String ) constructor not yet supported! See EventXmlToJava.javaToEventExpression()");
  }

	/**
	 * @param value
	 */
	public Expression( ResultType value ) {
		this.expression = value;
		if ( value != null ) {
		  resultType = (Class< ? extends ResultType >)value.getClass();
		}
		type = Type.Value;
	}

	/**
	 * @param parameter
	 */
	public Expression( Parameter< ResultType > parameter ) {
		this.expression = parameter;
		if ( parameter != null && parameter.getValueNoPropagate() != null ) {
		  resultType = (Class< ? extends ResultType >)parameter.getValueNoPropagate().getClass();
		}
		type = Type.Parameter;
	}

//	/**
//	 * @param function
//	 */
//	public Expression( Method method ) {
//		this.expression = method;
//		type = Type.Method;
//	}

	/**
	 * @param function
	 */
	public Expression( FunctionCall function ) {
		this.expression = function;
		if ( function != null && function.method != null ) {
		  resultType = (Class< ? extends ResultType >)function.method.getReturnType();
		}
		type = Type.Function;
	}
	
  /**
   * @param constructor
   */
  public Expression( ConstructorCall constructor ) {
    this.expression = constructor;
    if ( constructor != null ) {
      resultType = (Class< ? extends ResultType >)constructor.thisClass;
    }
    type = Type.Constructor;
  }
  
//	public Expression( Expression<ResultType> e ) {
//		this.expression = e.expression;
//		this.type = e.type;
//	}
	public Expression( Expression<ResultType> e, boolean deep ) {
		this.type = e.type;
		this.resultType = e.resultType;
		if ( !deep ) {
		  expression = e.expression;
		} else {
			switch (type) {
			case None:
//			case Method:
			case Value:  // TODO -- is this right for Value?
			case Parameter:
				expression = e.expression;
				break;
			case Function:
				expression = (Object) new FunctionCall((FunctionCall)e.expression);
				break;
      case Constructor:
        expression = (Object) new ConstructorCall((ConstructorCall)e.expression);
        break;
//			case Parameter:
//				expression = (Object) new Parameter<ResultType>((Parameter<ResultType>)e.expression);
//				break;
			default:
				Assert.assertTrue( "Error! Bad expression type!", false );
			}
		}
	}
	
	// default shallow
  public Expression( Expression<ResultType> e ) {
    this(e, false);
  }

  // REVIEW -- What if resultType == Expression.class?
	public ResultType evaluate( boolean propagate ) {//throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
	  if ( type == null || ( type != Type.None && expression == null ) ) {
	    return null;
	  }
		try {
		switch (type) {
		case None:
			try {
				throw new IllegalAccessException();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			return null; // TODO -- REVIEW -- exit?
		case Value:
			return (ResultType)expression;
		case Parameter:
          Parameter< ? > p = (Parameter< ? >)expression;
          while ( p != null ) {
            Object o = null;
            ResultType r = null;
            if ( propagate ) {
              o = p.getValue();
            } else {
              o = p.getValueNoPropagate();
            }
            try {
              if ( resultType != null ) {
                if ( resultType.isInstance( o ) ) {
                  return (ResultType)o;
                } else {
                  throw new ClassCastException();
                }
              }
              if ( o instanceof Parameter ) {
                p = (Parameter< ? >)o;
              } else if ( o instanceof Expression ) {
                return ( (Expression<ResultType>)o ).evaluate( propagate );
              } else {
                r = (ResultType)o;
                return r;
              }
            } catch ( ClassCastException cce ) {
              if ( o instanceof Parameter ) {
                p = (Parameter< ? >)o;
              } else if ( o instanceof Expression ) {
                return ( (Expression<ResultType>)o ).evaluate( propagate );
              } else {
                Debug.error( false, "Could not cast result of expression evaluation to ResultType: " + this );
                cce.printStackTrace();
              }
            }
          }
//		    return ((Parameter<ResultType>)expression).getValueNoPropagate();
////		case Method:
////			return (ResultType)((Method)expression).invoke(null, (Object[])null);
		case Constructor:
    case Function:
			return (ResultType)((Call)expression).evaluate( propagate );
		default:
			return null;
		}
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
//		} catch (IllegalAccessException e) {
//			e.printStackTrace();
//		} catch (InvocationTargetException e) {
//			e.printStackTrace();
		}
		return null;  // TODO -- REVIEW -- shouldn't get here -- die?
	}
	
	@Override
	public String toString() {
		switch (type) {
		case None:
			try {
				throw new IllegalAccessException();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			return null; // TODO -- REVIEW -- exit?
		case Value:
//			return ((ResultType)expression).toString();
		case Parameter:
//		case Method:
		case Function:
    case Constructor:
      if ( expression == null ) return "null";
      return expression.toString();
		default:
			return null;
		}
	}

	@Override
	public boolean substitute( Parameter<?> p1, Parameter<?> p2, boolean deep,
	                           Set<HasParameters> seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return false;
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return false;
		boolean subbed = false;
		if ( type == Type.Parameter ) {
			if ( p1 == expression ) {
				expression = p2;
				subbed = true;
			}
		}
		if ( !subbed && ( expression instanceof HasParameters ) ) {
			HasParameters gotParameters = (HasParameters) expression;
			assert( gotParameters != null );
			subbed = gotParameters.substitute( p1, p2, deep, seen );
		}
		return subbed;
	}
	
	@Override
	public Set< Parameter<?> > getParameters( boolean deep,
	                                          Set<HasParameters> seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
		Set< Parameter<?> > set = new HashSet< Parameter<?> >();
		if ( type == Type.Parameter ) {
		  if ( expression != null ) {
		    Parameter<?> p = (Parameter<?>) this.expression; 
		    set.add( p );
		    if ( deep ) {
		      Object v = p.getValueNoPropagate(); 
		      if ( v != null && v instanceof HasParameters ) {
		        set.addAll( ((HasParameters)v).getParameters( deep, seen ) );
		      }
		    }
		  }
		} else if ( expression instanceof HasParameters ) {
			HasParameters gotParameters = (HasParameters) expression;
			if ( gotParameters != null ) {
				set.addAll( gotParameters.getParameters( deep, seen ) );
			}
		}
		return set;
	}
	
  /**
   * @return the freeParameters
   */
  public Set< Parameter< ? > > getFreeParameters() {
    // REVIEW -- this assumes that the parameters of the constraint and their
    // freedom never change.
    if ( freeParameters == null ) {
      freeParameters = new HashSet< Parameter< ? > >();
      for ( Parameter< ? > p : getParameters( false, null ) ) {
        if ( p.getOwner() != null && 
             p.getOwner().isFreeParameter( p, false, null ) ) {
          freeParameters.add( p );
        }
      }
    }
    return freeParameters;
  }

  /**
   * @param freeParameters
   *          the freeParameters to set
   */
  public void setFreeParameters( Set< Parameter< ? > > freeParameters ) {
    this.freeParameters = freeParameters;
  }

	public Set<Parameter<?>> getFreeParameters( boolean deep,
	                                            Set<HasParameters> seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
	  return getFreeParameters();
	}

  @Override
  public void setFreeParameters( Set< Parameter< ? > > freeParams, boolean deep,
                                 Set<HasParameters> seen ) {
    setFreeParameters( freeParams );
  }
  
	@Override
	public boolean isGrounded(boolean deep, Set< Groundable > seen) {
		if (expression instanceof Groundable) {
			return ((Groundable)expression).isGrounded(deep, seen);
		}
		if ( expression == null ) {
			return false;
		}
		switch (type) {
		case Value:
//		case Method:
			return true; // since expression is not null
		case Parameter:
		case Function:
    case Constructor:
		case None:
		default:
			try {
				throw new IllegalAccessException();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			return false; // TODO -- REVIEW -- exit?
		}
	}

	@Override
	public boolean ground(boolean deep, Set< Groundable > seen) {
		if (expression instanceof Groundable) {
			return ((Groundable)expression).ground(deep, seen);
		}
		if ( expression == null ) {
			return false;
		}
		switch (type) {
		case Value:
			return true;
//		case Method:
//			return true; // since expression is not null
		case Parameter:
		case Function:
    case Constructor:
		case None:
		default:
			try {
				throw new IllegalAccessException();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			return false; // TODO -- REVIEW -- exit?
		}
		//return grounded;
	}

  // Not overriding since a superclass needs to implement a difft comparable,
  // and Java won't allow implementation of two difft Comparables!
  //@Override
  public int compareTo( Expression< ? > o ) {
    // TODO -- REVIEW -- Is this lame?
    return toString().compareTo( o.toString() );
  }

  @Override
  public boolean isStale() {
    for ( Parameter< ? > p : getParameters( false, null ) ) {
      if ( p.isStale() ) return true;
    }
    return false;
  }

  @Override
  public void setStale( boolean staleness ) {
    if ( Debug.isOn() ) Debug.outln( "setStale(" + staleness + ") to " + this );
    // TODO -- REVIEW -- Do nothing?
  }

  @Override
  public boolean hasParameter( Parameter< ? > parameter, boolean deep,
                               Set<HasParameters> seen ) {
    return getParameters( deep, seen ).contains( parameter );
  }

  @Override
  public boolean isFreeParameter( Parameter< ? > p, boolean deep,
                                  Set<HasParameters> seen ) {
    // REVIEW -- Should Expressions know which are free? Should it just be the
    // owner's job, in which case the Parameter can determine it itself?
    return false;
  }

  @Override
  public boolean isSatisfied(boolean deep, Set< Satisfiable > seen) {
    return HasParameters.Helper.isSatisfied( this, true, null );
  }

  @Override
  public boolean satisfy(boolean deep, Set< Satisfiable > seen) {
    return HasParameters.Helper.satisfy( this, true, null );
  }

  @Override
  public Domain< ResultType > getDomain( boolean propagate, Set< HasDomain > seen ) {
    if ( expression == null ) {
      if ( type == Type.Value ) {
        return SingleValueDomain.getNullDomain();
      }
      return null;
    }
    switch (type) {
    case Value:
//    case Method:
      return new SingleValueDomain<ResultType>( (ResultType)expression ); // since expression is not null
    case Parameter:
      return ((Parameter<ResultType>)expression).getDomain( propagate, seen );
    case Function:
    case Constructor:
      return (Domain< ResultType >)((FunctionCall)expression).getDomain( propagate, seen );
    case None:
    default:
      try {
        throw new IllegalAccessException();
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      }
      return null; // TODO -- REVIEW -- exit?
    }
  }

  @Override
  public Set< TimeVarying< ? >>
      getTimeVaryingObjects( boolean deep, Set< HasTimeVaryingObjects > seen ) {
    // TODO -- use HasParameters.Helper!!
    Pair< Boolean, Set< HasTimeVaryingObjects > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
    Set< TimeVarying<?> > set = new HashSet< TimeVarying<?> >();
    set.addAll( HasTimeVaryingObjects.Helper.getTimeVaryingObjects( expression, deep, seen ) );
    // REVIEW -- We could make Call extend HasTimeVaryingObject, but it seems
    // like everybody has to know about everybody!
    // What about a general Has that has a get( Class<?> c, deep, seen )?
    // Consider again using reflection to go through members?
    if ( deep && ( type == Type.Function || type == Type.Constructor ) ) {
      Call call = (Call)expression;
      set.addAll( HasTimeVaryingObjects.Helper.getTimeVaryingObjects( call.getObject(), deep, seen ) );
      set.addAll( HasTimeVaryingObjects.Helper.getTimeVaryingObjects( call.getArguments(), deep, seen ) );
    }
    return set;
  }
  
}

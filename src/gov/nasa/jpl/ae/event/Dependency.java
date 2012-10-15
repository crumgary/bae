package gov.nasa.jpl.ae.event;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.Assert;

import gov.nasa.jpl.ae.event.Functions.Equals;
import gov.nasa.jpl.ae.solver.Constraint;
import gov.nasa.jpl.ae.solver.Domain;
import gov.nasa.jpl.ae.solver.HasConstraints;
import gov.nasa.jpl.ae.solver.Random;
import gov.nasa.jpl.ae.solver.Satisfiable;
import gov.nasa.jpl.ae.solver.Variable;
import gov.nasa.jpl.ae.util.Debug;
import gov.nasa.jpl.ae.util.Pair;
import gov.nasa.jpl.ae.util.Utils;



/**
 * 
 */

/**
 * @author bclement
 * 
 */

// TODO -- REVIEW -- Staleness can be managed with a bit string (one bit for
// each node): Each node has a mask for setting downstream sinks (or checking
// upstream sources).

// TODO -- REVIEW -- Should this class be removed and have dependencies captured
// as time-invariant effects? Or should effects be TimeVarying Dependencies?

// TODO -- REVIEW -- How about constructing a constraint from a dependency
// ( parameter == expression )

// TODO -- REVIEW -- Should dependencies be applicable to a time period, like
// constraints (& effects)?
public class Dependency< T > 
             implements HasParameters, ParameterListener, Constraint,
                        LazyUpdate, HasConstraints {

  protected Parameter< T > parameter;
  protected Expression< T > expression;
  private ConstraintExpression constraint = null;
  protected boolean refreshing = false; // to prevent propagation cycles

  public Dependency( Parameter< T > p, Expression< T > e ) {
    parameter = p;
    parameter.setStale( true );
    expression = e;
  }

  public Dependency( Dependency< T > d ) {
    parameter = new Parameter< T >( d.parameter );
    expression = new Expression< T >( d.expression, true );
  }

  public void apply() {
    apply( true );
  }
  public void apply( boolean propagate ) {
    // TODO -- REVIEW -- if ( isStale() ) ??
    Debug.outln( "calling apply(" + propagate + ") on dependency " + this );
    T val = expression.evaluate(propagate);
    if ( parameter.isStale() || val != parameter.getValueNoPropagate() ) {
      parameter.setValue( val, propagate );
    }
  }

  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.solver.Satisfiable#isSatisfied()
   */
  @Override
  public boolean isSatisfied(boolean deep, Set< Satisfiable > seen) {
    Pair< Boolean, Set< Satisfiable > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return true;
    seen = pair.second;
    boolean sat;
    if ( constraint != null ) {
      sat = constraint.isSatisfied(deep, seen);
      Debug.outln( "Dependency.isSatisfied(): constraint not satisfied: " );// + this );
    } else if ( !parameter.isGrounded(deep, null) ) {
      sat = false;
      parameter.setStale( true );
      Debug.outln( "Dependency.isSatisfied(): parameter not grounded: " );// + this );
    } else if ( !expression.isGrounded(deep, null) ) {
      sat = false;
      parameter.setStale( true );
      Debug.outln( "Dependency.isSatisfied(): expression not grounded: " );// + this );
    } else if ( !parameter.isSatisfied(deep, null) ) {
      sat = false;
      Debug.outln( "Dependency.isSatisfied(): parameter not satisfied: " );// + this );
    } else if ( !expression.isSatisfied(deep, null) ) {
      sat = false;
      Debug.outln( "Dependency.isSatisfied(): expression not satisfied: " );// + this );
    } else {
      T value = expression.evaluate(false);
      T pValue = parameter.getValueNoPropagate();
      sat = Parameter.valuesEqual( pValue, value );//pValue == value || ( pValue != null && pValue.equals( value ) );
      if ( !sat ) {
        parameter.setStale( true );
        Debug.outln( "Dependency.isSatisfied(): parameter value (" + pValue
                     + ") not equal to evaluated expression (" + value + "): " ); // + this );
      }
    }
    Debug.outln( "Dependency.isSatisfied() = " + sat + ": " + this );
    return sat;
  }

  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.solver.Satisfiable#satisfy()
   */
  @Override
  public boolean satisfy(boolean deep, Set< Satisfiable > seen) {
    Pair< Boolean, Set< Satisfiable > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return true;
    seen = pair.second;
    if ( isSatisfied(deep, null) ) return true;
//    if ( Random.global.nextDouble() < 0.2 ) {
//      return getConstraintExpression().satisfy( deep, seen );
//    }
    Debug.outln("Dependency.satisfy() calling ground: " + this );
    expression.ground(deep, null);
    expression.satisfy(deep, seen);
    if ( expression.isGrounded(deep, null) ) {
      Debug.outln("Dependency.satisfy() grounded, evaluating expression: " + this );
      T value = expression.evaluate(true);
      Debug.outln("Dependency.satisfy() evaluated expression, setting value to " + value + ": " + this );
      parameter.setValue( value );
      Debug.outln("Dependency.satisfy() set value: " + this );
      return ( value != null );
    } else {
      parameter.satisfy(deep, seen);
    }
    return false;
  }

  public ConstraintExpression getConstraintExpression() {
    if ( constraint == null ) {
      Equals< T > eq =
          new Functions.Equals< T >( new Expression< T >( parameter ),
                                     expression );
      constraint = new ConstraintExpression( eq );
    }
    return constraint;
  }
  
  public boolean evaluate() {
    return satisfy( true, null );
  }

  @Override
  public boolean substitute( Parameter< ? > t1, Parameter< ? > t2, boolean deep,
                             Set< HasParameters > seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return false;
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return false;
    boolean subbed = false;
    if ( parameter == t1 ) {
      parameter = (Parameter< T >)t2;
      subbed = true;
    }
    if ( expression.substitute( t1, t2, deep, seen ) ) {
      subbed = true;
    }
    return subbed;
  }

  // Gather the parameters of the expression and the dependent parameter.
  @Override
  public Set< Parameter< ? > > getParameters( boolean deep,
                                              Set< HasParameters > seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
    HashSet< Parameter< ? > > set = new HashSet< Parameter< ? > >();
    set.add( parameter );
    set.addAll( expression.getParameters( deep, seen ) );
    return set;
  }

  // Add all parameters except the dependent parameter.
  @Override
  public Set< Parameter< ? > > getFreeParameters( boolean deep,
                                                  Set< HasParameters > seen) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
    HashSet< Parameter< ? > > set = new HashSet< Parameter< ? > >();
    set.addAll( expression.getParameters( deep, seen ) );
    return set;
  }

  @Override
  public void setFreeParameters( Set< Parameter< ? >> freeParams, boolean deep,
                                 Set< HasParameters > seen ) {
    Assert.assertTrue( "This method is not supported!", false );
    // TODO Auto-generated method stub
  }
  
  @Override
  public Set< Variable< ? > > getVariables() {
    Set< Variable< ? > > s = new HashSet< Variable< ? > >();
    s.addAll( getParameters( false, null ) );
    return s;
  }

  /* (non-Javadoc)
   * @see solver.Constraint#pickValue(solver.Variable)
   */
  @Override
  public < T1 > boolean pickValue( Variable< T1 > variable ) {
    Debug.outln( "Dependency.pickValue(" + variable + ") begin" );
    if ( variable == this.parameter ) {
      Object value = variable.getValue( false ); // DON'T CHANGE false
      if ( refresh( this.parameter ) ) {
        if ( !Parameter.valuesEqual( variable.getValue( true ), value ) ) { // DON'T CHANGE true
          Debug.outln( "Dependency.pickValue(" + variable + ") returning refreshed value" );
          return true;
        }
      }
      Variable< ? > var = pickRandomFreeVariable();
      if ( var == null ) var = pickRandomVariable();
      if ( var == null ) return false;
      Constraint c = getConstraintExpression();
      boolean changedSomething = false;
      if ( c != null ) {
        if ( c.pickValue( var ) ) changedSomething = true;
      } else {
        if ( var.pickValue() ) changedSomething = true;
      }
      Debug.outln( "Dependency.pickValue(" + variable + ") returns "
                   + changedSomething + " for target/sink param" );
      return changedSomething;
    }
    if ( variable instanceof Parameter
         && !hasParameter( (Parameter< T1 >)variable, false, null ) ) {
      return false;
    }
    Constraint c = getConstraintExpression();
    if ( c != null ) {
      if ( c.pickValue( variable ) ) return true;
    }
    // TODO Auto-generated method stub
    return false;
  }

  protected Variable< ? > pickRandomVariable() {
    Set< Variable< ? > > vars = getVariables();
    if ( !Utils.isNullOrEmpty( vars ) ) {
      int i = Random.global.nextInt( vars.size() );
      Variable<?> v = (Variable<?>)(vars.toArray())[i];
      return v;
    }
    return null;
  }

  protected Variable< ? > pickRandomFreeVariable() {
    Set< Variable< ? > > vars = getFreeVariables();
    if ( !Utils.isNullOrEmpty( vars ) ) {
      int i = Random.global.nextInt( vars.size() );
      Variable<?> v = (Variable<?>)(vars.toArray())[i];
      return v;
    }
    return null;
  }

  @Override
  public < T1 > boolean restrictDomain( Variable< T1 > v ) {
    if ( v == parameter ) {
      T val = expression.evaluate(true);
      Domain<T1> d = v.getDomain().clone();
      d.restrictToValue( (T1)val );
      v.setDomain( d );
    } else {
      getConstraintExpression().restrictDomain( v );
    }
    return v.getDomain() != null && v.getDomain().size() > 0; 
  }

  @Override
  public < T1 > boolean isFree( Variable< T1 > v ) {
    return ( v != parameter );
  }

  @Override
  public < T1 > boolean isDependent( Variable< T1 > v ) {
    return ( v == parameter );
  }

  @Override
  public Set< Variable< ? > > getFreeVariables() {
    Set< Variable< ? > > set = getVariables();
    set.remove( parameter );
    return set;
  }

  @Override
  public void setFreeVariables( Set< Variable< ? > > freeVariables ) {
    try {
      throw new Exception( "Dependency.setFreeVariables() is not supported." );
    } catch ( Exception e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public int compareTo( Constraint o ) {
    if ( o instanceof Dependency ) {
      Dependency< ? > od = (Dependency< ? >)o;
      int compare = parameter.compareTo( od.parameter );
      if ( compare != 0 ) return compare;
      compare = expression.compareTo( od.expression );
      if ( compare != 0 ) return compare;
      return this.toString().compareTo( od.toString() );
    }
    return ((Object)this).getClass().getName().compareTo( o.getClass().getName() );
  }

  @Override
  public void handleValueChangeEvent( Parameter< ? > parameter ) {
    if ( getParameters( true, null ).contains( parameter )
         && this.parameter != parameter ) {
      apply( false );
    }
  }

  @Override
  public void handleDomainChangeEvent( Parameter< ? > parameter ) {
    // TODO -- REVIEW -- Anything to do?
  }

  @Override
  public String getName() {
    // TODO Auto-generated method stub
    return getClass().getName();
  }

  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("Dependency(");
    if ( parameter == null ) {
      sb.append("null");
    } else {
      sb.append( parameter.toString( true, false, true, null ) );
    }
    sb.append( " <-- " + expression );
    sb.append(")");
    return sb.toString();
  }

  @Override
  public boolean isStale() {
    boolean parameterStale = parameter.isStale();
    boolean expressionStale = expression.isStale();
    return parameterStale || expressionStale;
  }

  @Override
  public void setStale( boolean staleness ) {
    Debug.outln( "setStale(" + staleness + ") to " + this );
    parameter.setStale( staleness );
  }

  @Override
  public boolean refresh( Parameter< ? > parameter ) {
    if ( this.parameter == parameter ) {
      if ( !refreshing ) {
        refreshing = true;
        apply();
        refreshing = false;
        return true;
      }
    }
    return false;
  }

  @Override
  public void setStaleAnyReferencesTo( Parameter< ? > changedParameter ) {
    if ( expression.hasParameter( changedParameter, false, null ) ) {
      parameter.setStale( true );
    }
  }

  @Override
  public boolean hasParameter( Parameter< ? > parameter, boolean deep,
                               Set< HasParameters > seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return false;
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return false;
    return getParameters( deep, seen ).contains( parameter );
  }

  @Override
  public boolean isFreeParameter( Parameter< ? > p, boolean deep,
                                  Set< HasParameters > seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return false;
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return false;
    if ( p == parameter ) return false;
    return expression.isFreeParameter( p, deep, seen );
  }

  @Override
  public Collection< Constraint > getConstraints( boolean deep,
                                                  Set< HasConstraints > seen ) {
    Pair< Boolean, Set< HasConstraints > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
    Set< Constraint > set = new HashSet< Constraint >();
    set.add( this );
    if ( deep ) {
      Set< Constraint > pSet =
          HasConstraints.Helper.getConstraints( getParameters( false, null ),
                                                deep, seen );
      if ( pSet.size() > set.size() ) {
        pSet.addAll( set );
        set = pSet;
      } else {
        set.addAll( pSet );
      }
    }
    return set;
  }

//  /* (non-Javadoc)
//   * @see gov.nasa.jpl.ae.event.ParameterListener#pickValue(gov.nasa.jpl.ae.event.Parameter)
//   */
//  @Override
//  public boolean pickValue( Parameter< ? > parameter ) {
//    return pickValue((Variable< ? >) parameter);
//  }

}

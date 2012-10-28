package gov.nasa.jpl.ae.event;

import gov.nasa.jpl.ae.solver.Constraint;
import gov.nasa.jpl.ae.solver.ConstraintLoopSolver;
import gov.nasa.jpl.ae.solver.HasConstraints;
import gov.nasa.jpl.ae.solver.Satisfiable;
import gov.nasa.jpl.ae.util.CompareUtils;
import gov.nasa.jpl.ae.util.Debug;
import gov.nasa.jpl.ae.util.Pair;
import gov.nasa.jpl.ae.util.Timer;
import gov.nasa.jpl.ae.util.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;

import junit.framework.Assert;



/**
 * 
 */

/**
 * @author bclement
 * 
 */
public class DurativeEvent extends ParameterListenerImpl implements Event, Cloneable,
                           HasEvents, Groundable, Satisfiable,
                           //Comparable< Event >, 
                           ParameterListener,
                           HasTimeVaryingObjects {

  // Static members
  
  protected static int counter = 0;

  // Other Members

  public Timepoint startTime = new Timepoint( "startTime", this );
  public Duration duration = new Duration( this );
  public Timepoint endTime = new Timepoint( "endTime", this );
  // TODO -- REVIEW -- create TimeVariableParameter and EffectMap classes for
  // effects?
  protected List< Pair< Parameter< ? >, Set< Effect > > > effects =
      new ArrayList< Pair< Parameter< ? >, Set< Effect > > >();
  protected Map< ElaborationRule, Vector< Event > > elaborations =
      new TreeMap< ElaborationRule, Vector< Event > >();

  protected Dependency startTimeDependency = null;
  
  protected Dependency endTimeDependency = null;
  
  protected Dependency durationDependency = null;
  
  // TODO -- consider breaking elaborations up into separate constraints
  protected Constraint elaborationsConstraint = 
      new AbstractParameterConstraint() {

    @Override
    public boolean satisfy(boolean deep, Set< Satisfiable > seen) {
      Pair< Boolean, Set< Satisfiable > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return true;
      seen = pair.second;
      boolean satisfied = true;
      if ( !DurativeEvent.this.startTime.isGrounded(deep, null) ) return false;

      // Don't elaborate outside the horizon.  Need startTime grounded to know.
      if ( !startTime.isGrounded(deep, null) ) return false;
      if ( startTime.getValue() >= Timepoint.getHorizonDuration() ) {
        if ( Debug.isOn() ) Debug.outln( "satisfyElaborations(): No need to elaborate event outside the horizon: "
                     + getName() );
        return true;
      }
      List< Pair< ElaborationRule, Vector< Event >>> list =
          new ArrayList< Pair< ElaborationRule, Vector< Event >>>();
      for ( Entry< ElaborationRule, Vector< Event > > er : elaborations.entrySet() ) {
            list.add( new Pair< ElaborationRule, Vector< Event >>( er.getKey(),
                                                                   er.getValue() ) );
      }
      elaborations.clear();
      for ( Pair< ElaborationRule, Vector< Event > > p : list ) {
        ElaborationRule r = p.first;
        Vector< Event > events = p.second;
        if ( isElaborated( events ) != r.isConditionSatisfied() ) {
          if ( r.attemptElaboration( events, true ) ) {
            if ( !r.isConditionSatisfied() ) satisfied = false;
          } else {
            if ( r.isConditionSatisfied() ) satisfied = false;
          }
        }
        elaborations.put( r, events );
      }
      return satisfied;
    }
    
    @Override
    public boolean isSatisfied(boolean deep, Set< Satisfiable > seen) {
      Pair< Boolean, Set< Satisfiable > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return true;
      seen = pair.second;
      for ( Entry< ElaborationRule, Vector< Event > > er : elaborations.entrySet() ) {
        if ( !startTime.isGrounded(deep, null) ) return false;
        if ( startTime.getValueNoPropagate() < Timepoint.getHorizonDuration() ) {
          if ( isElaborated( er ) != er.getKey().isConditionSatisfied() ) {
            return false;
          }
        }
      }
      return true;
    }
    
    @Override
    public Set< Parameter< ? > > getParameters( boolean deep,
                                                Set<HasParameters> seen ) {
      Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return Utils.getEmptySet();
      seen = pair.second;
      //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
      Set< Parameter< ? > > s = new TreeSet< Parameter< ? > >();
      for ( Entry< ElaborationRule, Vector< Event > > er : elaborations.entrySet() ) {
        s = Utils.addAll( s, er.getKey().getCondition().getParameters( deep, seen ) );
      }
      return s;
    }

    @Override
    public String toString() {
      // TODO -- should make this look evaluable, ex: condition -> eventExists
      return getName() + ".elaborationsConstraint";
    }

    @Override
    public void setFreeParameters( Set< Parameter< ? > > freeParams,
                                   boolean deep, Set< HasParameters > seen ) {
      Assert.assertFalse( "setFreeParameters() not supported!", true );
      //if ( Utils.seen( this, deep, seen ) ) return;
    }
    
  };  // end of elaborationsConstraint
  
  // TODO -- consider breaking effects into separate constraints
  protected Constraint effectsConstraint = new AbstractParameterConstraint() {

    protected boolean
      areEffectsOnTimeVaryingSatisfied( Parameter< ? > variable,
                                        Set< Effect > effects,
                                        boolean deep,
                                        Set< Satisfiable > seen ) {
      boolean deepGroundable = deep;
      Set< Groundable > seenGroundable = null;
      if ( !variable.isGrounded(deepGroundable, seenGroundable) ) return false;
      if ( !variable.isSatisfied(deep, seen) ) return false;
      for ( Effect e : effects ) {
        if ( !e.isApplied( variable )
             || !variable.isGrounded( deepGroundable, seenGroundable ) ) {
          return false;
        }
      }
      return true;
    }

    protected boolean
        satisfyEffectsOnTimeVarying( Parameter< ? > variable,
                                     Set< Effect > effects,
                                     boolean deep,
                                     Set< Satisfiable > seen ) {
      boolean deepGroundable = deep;
      Set< Groundable > seenGroundable = null;
      boolean satisfied = true;
      if ( !variable.isGrounded(deepGroundable, seenGroundable) ) {
        variable.ground(deepGroundable, seenGroundable);
      }
      if ( !variable.isSatisfied(deep, null) ) {
        variable.satisfy(deep, seen);
      }
      for ( Effect e : effects ) {
        if ( !e.isApplied( (Parameter< ? >)variable ) ) {
          if ( !variable.isGrounded(deepGroundable, seenGroundable) ) {
            satisfied = false;
          } else {
        	  Object value = variable.getValue();
        	  if ( (!(value instanceof TimeVarying )) && value instanceof Parameter) {
        		 return satisfyEffectsOnTimeVarying((Parameter<?>) value, effects,
        		                                    deep, seen); 
        	  } else if (value instanceof TimeVarying) {
        		  e.applyTo( (TimeVarying<?>) variable.getValueNoPropagate(), true );
        	  }
          }
        }
      }
      return satisfied;
    }
    
    @Override
    public boolean satisfy(boolean deep, Set< Satisfiable > seen) {
      Pair< Boolean, Set< Satisfiable > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return true;
      seen = pair.second;
      boolean satisfied = true;
      for ( Pair< Parameter< ? >, Set< Effect > > p : effects ) {
        Parameter< ? > variable = p.first;
        Set< Effect > set = p.second;
        if ( !satisfyEffectsOnTimeVarying( variable, set, deep, seen ) ) {
          satisfied = false;
        }
      }
      return satisfied;
    }
    
    @Override
    public boolean isSatisfied(boolean deep, Set< Satisfiable > seen) {
      Pair< Boolean, Set< Satisfiable > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return true;
      seen = pair.second;
      for ( Pair< Parameter< ? >, Set< Effect > > p : effects ) {
        Parameter< ? > variable = p.first;
        Set< Effect > set = p.second;
        if ( !areEffectsOnTimeVaryingSatisfied( variable, set, deep, seen ) ) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Set< Parameter< ? > > getParameters( boolean deep,
                                               Set<HasParameters> seen ) {
      Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return Utils.getEmptySet();
      seen = pair.second;
      //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
      Set< Parameter< ? > > s = new TreeSet< Parameter< ? > >();
      for ( Set< Effect > set : Pair.getSeconds( getEffects() ) ) {//.values() ) {
        for ( Effect e : set ) {
          if ( e instanceof HasParameters ) {
            s = Utils.addAll( s, ( (HasParameters)e ).getParameters( deep, seen ) );
          }
        }
      }
      return s;
    }

    @Override
    public String toString() {
      // TODO -- maybe make this look evaluable, ex: v.getValue(t) == fCall.arg0
      return getName() + ".effectsConstraint";
    }

    @Override
    public void setFreeParameters( Set< Parameter< ? >> freeParams,
                                   boolean deep, Set< HasParameters > seen ) {
      Assert.assertFalse( "setFreeParameters() is not supported!", true );
    }
  };  // end of effectsConstraint

  
  // Constructors

  public DurativeEvent() {
    this((String)null);
  }
  public DurativeEvent( String name ) {
    setName( name );
    loadParameters();

    // create the dependency, endTime = startTime + duration
    // TODO -- REVIEW -- should this dependency just be a constraint instead?
    Functions.Sum< Integer, Integer > sum =
        new Functions.Sum< Integer, Integer >(
            new Expression< Integer >( startTime ),
            new Expression< Integer >( duration ) );
    endTimeDependency  = addDependency( endTime, sum );
    Functions.Sub< Integer, Integer > sub1 =
        new Functions.Sub< Integer, Integer >(
            new Expression< Integer >( endTime ),
            new Expression< Integer >( duration ) );
    startTimeDependency = addDependency( startTime, sub1 );
    Functions.Sub< Integer, Integer > sub2 =
        new Functions.Sub< Integer, Integer >(
            new Expression< Integer >( endTime ),
            new Expression< Integer >( startTime ) );
    durationDependency = addDependency( duration, sub2 );
  }

  public DurativeEvent( DurativeEvent durativeEvent ) {
    this( null, durativeEvent );
  }
  public DurativeEvent( String name, DurativeEvent durativeEvent ) {
    setName( name );
    copyParameters( durativeEvent );
    loadParameters();

    // copy containers after clearing
    constraintExpressions.clear();
    effects.clear();
    dependencies.clear();
    for ( ConstraintExpression c : durativeEvent.constraintExpressions ) {
      ConstraintExpression nc = new ConstraintExpression( c );
      constraintExpressions.add( nc );
    }
//    for ( Map.Entry< Parameter< ? >, Set< Effect > > e : 
//          durativeEvent.effects.entrySet() ) {
    for ( Pair< Parameter< ? >, Set< Effect > > p : durativeEvent.effects ) {
      Set< Effect > ns = new TreeSet< Effect >();
      try {
        for ( Effect eff : p.second ) {
          Effect ne = eff.clone();
          ns.add( ne );
        }
      } catch ( CloneNotSupportedException e1 ) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      effects.add( new Pair< Parameter< ? >, Set< Effect >>( (Parameter< ? >)p.first.clone(),
                                                             ns ) );
    }
    for ( Dependency< ? > d : durativeEvent.dependencies ) {
      Dependency< ? > nd = new Dependency( d );
      dependencies.add( nd );
    }

    // We need to make sure the copied constraints are on this event's
    // parameters and not that of the copied constraints.
    List< Pair< Parameter< ? >, Parameter< ? > > > subList =
        buildSubstitutionList( durativeEvent );
    for ( Pair< Parameter< ? >, Parameter< ? > > p : subList ) {
      substitute( p.first, p.second, false, null );
    }
    // }
  }

  public void fixTimeDependencies() {
  }
  public void fixTimeDependencies1() {
    boolean gotStart = false, gotEnd = false, gotDur = false;
    boolean stillHaveStart = false, stillHaveEnd = false, stillHaveDur = false;
    int numGot = 0;
    int numHave = 0;
    // see what time dependencies we have
    for ( Dependency< ? > d : getDependencies() ) {
      if ( d.parameter == startTime ) { 
        if ( d == startTimeDependency ) {
          if ( !stillHaveStart ) {
            ++numHave;
            stillHaveStart = true;
          }
        } else {
          if ( !gotStart ) {
            gotStart = true;
            ++numGot;
          }
        }
      } else if ( d.parameter == endTime ) {
        if ( d == endTimeDependency ) {
          if ( !stillHaveEnd ) {
            stillHaveEnd = true;
            ++numHave;
          }
        } else {
          if ( !gotEnd ) {
            gotEnd = true;
            ++numGot;
          }
        }
      } else if ( d.parameter == duration ) {
        if ( d == durationDependency ) {
          if ( !stillHaveDur ) {
            stillHaveDur = true;
            ++numHave;
          }
        } else {
          if ( !gotDur ) {
            gotDur = true;
            ++numGot;
          }
        }
      }
    }
    // get rid of already replaced dependencies
    if ( gotStart && stillHaveStart ) {
      getDependencies().remove( startTimeDependency );
      stillHaveStart = false;
      --numHave;
    }
    if ( gotEnd && stillHaveEnd ) {
      getDependencies().remove( endTimeDependency );
      stillHaveEnd = false;
      --numHave;
    }
    if ( gotDur && stillHaveDur ) {
      getDependencies().remove( durationDependency );
      stillHaveDur = false;
      --numHave;
    }
    // only want one dependency among the three
    if ( numHave > 1 ) {
      if ( stillHaveEnd ) {
        if ( stillHaveStart ) {
          getDependencies().remove( startTimeDependency );
        }
        if ( stillHaveDur ) {
          getDependencies().remove( durationDependency );
        }
      } else if ( stillHaveDur ) {
        if ( stillHaveStart ) {
          getDependencies().remove( startTimeDependency );
        }
      } else {
        assert false;
      }
    }
  }
  
  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#substitute(gov.nasa.jpl.ae.event.Parameter, gov.nasa.jpl.ae.event.Parameter, boolean)
   */
  @Override
  public boolean substitute( Parameter< ? > p1, Parameter< ? > p2, boolean deep,
                             Set<HasParameters> seen ) {
    // Parent class adds 'this' to seen, so just checking here.
    if ( !Utils.isNullOrEmpty( seen ) && seen.contains( this ) ) return false;
    // call parent class's method
    boolean subbed = super.substitute( p1, p2, deep, seen );
    boolean s = HasParameters.Helper.substitute( effects, p1, p2, deep, null );
    subbed = subbed || s;
//    for ( Entry< Parameter< ?>, Set< Effect >> e : effects.entrySet() ) {
//      if ( e.getValue() instanceof HasParameters ) {
//        boolean s = ( (HasParameters)e.getValue() ).substitute( p1, p2, deep, seen );
//        subbed = subbed || s;
//      }
//    }
    return subbed;
  }

  // Methods

  // Subclasses should override this to add their own parameters.
  protected void loadParameters() {
    parameters.clear();
    parameters.add( startTime );
    parameters.add( duration );
    parameters.add( endTime );
  }

  // Subclasses should override this to add their own parameters.
  // TODO -- Can this just iterate through parameters container to avoid
  // overriding in subclasses?
  protected void copyParameters( DurativeEvent event ) {
    // Inefficiently reallocating these parameters--maybe create
    // Parameter.copy(Parameter)
    startTime = new Timepoint( event.startTime );
    duration = new Duration( event.duration );
    endTime = new Timepoint( event.endTime );
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#clone()
   */
  @Override
  public Object clone() {
    return new DurativeEvent( this );
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append( getClass().getName() );
    Parameter<?> firstParams[] = { startTime, duration, endTime };  // Could use Arrays.sort() .search()
    List< Parameter< ? > > allParams = new ArrayList< Parameter< ? > >(Arrays.asList( firstParams ));
    Set< Parameter< ? > > restParams = getParameters( false, null );
    restParams.removeAll( allParams );
    allParams.addAll( restParams );
    for ( Object p : allParams ) {
      if ( p instanceof Parameter ) {
        sb.append( ", " + ((Parameter<?>)p).toString( false, false, true, null ) );
      } else {
        sb.append( ", " + p.toString() );
      }
    }
    return sb.toString();
  }

  public void executeAndSimulate() {
    executeAndSimulate( 1.0e12 );
  }
  
  public void executeAndSimulate( double timeScale ) {
    Timer timer = new Timer();
    amTopEventToSimulate = true;
    execute();
    System.out.println("execution:\n" + executionToString());
    simulate(timeScale);
    amTopEventToSimulate = false;
    System.out.println("Finished executing and simulating:");
    System.out.println( timer.toString() );
  }
  
  /* (non-Javadoc)
   * @see event.Event#execute()
   */
  @Override
  public void execute() {
    // REVIEW -- differentiate between execute for simulation and execute in
    // external environment?
    if ( Debug.isOn() ) Debug.outln( getName() + ".execute()" );
    Timer timer = new Timer();
    System.out.println( getName() + ".execute(): starting stop watch" );
    boolean satisfied = satisfy( true, null );
    if ( Debug.isOn() ) Debug.outln( getName() + ".execute() called satisfy() --> " + satisfied );
//    if ( !satisfied ) {
//      satisfied = solver.solve( getConstraints( true, null ) );
//      if ( Debug.isOn() ) Debug.outln( getName() + ".execute() called solve() --> " + satisfied );
//    }
    timer.stop();
    System.out.println( "\n" + getName() + ".execute(): Time to elaborate and resolve constraints:" );
    System.out.println( timer );
    timer.start();
    Collection<Constraint> constraints = getConstraints( true, null );
    System.out.println("All " + constraints.size() + " constraints: ");
    for (Constraint c : constraints) {
    	System.out.println("Constraint: " + c);
    }
    if ( satisfied ) {
      System.out.println( "\nAll constraints were satisfied!" );
    } else {
      Collection< Constraint > unsatisfiedConstraints =
          ConstraintLoopSolver.getUnsatisfiedConstraints( constraints );
      if ( unsatisfiedConstraints.isEmpty() ) {
        System.out.println( (constraints.size() - unsatisfiedConstraints.size())
                            + " out of " + constraints.size() 
                            + " constraints were satisfied!" );
        System.err.println( getName() + "'s constraints were not satisfied!" );
      } else {
        System.err.println( "Could not resolve the following "
                            + unsatisfiedConstraints.size()
                            + " constraints for " + getName() + ":" );
        for ( Constraint c : unsatisfiedConstraints ) {
          c.isSatisfied( false, null ); // can look shallow since constraints
                                        // were gathered deep!
          System.err.println( c.toString() );
        }
      }
    }

    System.out.println( "\n" + getName() + ".execute(): Time to gather and write out constraints:" );
    System.out.println( timer );
    
    // HACK -- Sleeping to separate system.err from system.out.
    try {
      Thread.sleep( 100 );
    } catch ( InterruptedException e1 ) {
      e1.printStackTrace();
    }

  }
  
  public EventSimulation createEventSimulation() {
    EventSimulation sim = new EventSimulation( getEvents( true, null ), 1.0e12 );
    sim.add( this );
    Set< TimeVarying< ? > > tvs = getTimeVaryingObjects( true, null );
    for ( TimeVarying< ? > tv : tvs ) {
      if ( tv instanceof TimeVaryingMap ) {
        sim.add( (TimeVaryingMap< ? >)tv );
      }
    }
    return sim;
  }

  public void simulate( double timeScale ) {
    simulate( timeScale, System.out );
  }

  public void simulate( double timeScale, java.io.OutputStream os ) {
    simulate( timeScale, os, true );
  }
  
  public void simulate( double timeScale, java.io.OutputStream os, boolean runPlotter ) {
    Debug.outln( "\nsimulate( timeScale=" + timeScale + ", runPlotter="
                 + runPlotter + " ): starting stop watch\n" );
    Timer timer = new Timer();
    try {
      EventSimulation sim = createEventSimulation();
      sim.tryToPlot = runPlotter;
      sim.simulate( timeScale, os );
    } catch ( Exception e ) {
      e.printStackTrace();
    }
    Debug.outln( "\nsimulate( timeScale=" + timeScale + ", runPlotter="
        + runPlotter + " ): completed\n" + timer + "\n" );
  }

  @Override
  public void doSnapshotSimulation( boolean improved ) {
    boolean didntWriteFile = true;
    // augment file name
    String fileName = this.baseSnapshotFileName;
    if ( fileName != null ) {
      int pos = fileName.lastIndexOf( '.' );
      String pkg = getClass().getPackage().getName();
      if ( pkg != null ) {
        if ( pos == -1 ) pos = fileName.length();
        fileName =
            fileName.substring( 0, pos ) + "." + pkg + fileName.substring( pos );
      }
      String bestFileName = "best_" + fileName;
      if ( !snapshotToSameFile ) {
        fileName = Utils.addTimestampToFilename( fileName );
      }
      File file = new File( fileName );

      // write out simulation to file
      System.out.println( "writing simulation snapshot to "
                          + file.getAbsolutePath() );
      try {
        System.out.println( "  which is the same as " + file.getCanonicalPath() );
      } catch ( IOException e1 ) {
        // ignore
      }
      if ( writeSimulation( file ) ) {
        didntWriteFile = false;
      }
      if ( improved ) {
        writeSimulation( bestFileName );
      }
    }
    if ( didntWriteFile ) {
      writeSimulation( System.out );
    }
  }
  
  public boolean writeSimulation( String fileName ) {
    File file = new File(fileName);
    return writeSimulation( file );
  }
  public boolean writeSimulation( File file ) {
    boolean succ = false;
    FileOutputStream os = null;
    try {
      os = new FileOutputStream( file );
      succ = writeSimulation( os );
    } catch ( FileNotFoundException e ) {
      System.err.println( "Writing simulation output to file "
                          + file.getAbsolutePath() + " failed" );
      e.printStackTrace();
    }
    try {
      os.close();
    } catch ( IOException e ) {
      System.err.println( "Trouble closing output file "
                          + file.getAbsolutePath() );
      e.printStackTrace();
    }
    return succ;
  }
  public boolean writeSimulation( OutputStream os ) {
    PrintWriter w = new PrintWriter( os, true );
    w.println( "remaining "
               + solver.getUnsatisfiedConstraints().size()
               + " unsatisfied constraints: "
               + Utils.join( solver.getUnsatisfiedConstraints(),
                             "\nConstraint: " ) );
    simulate( 1e15, os, false );
    return true;
  }
  // Create an ElaborationRule for constructing an eventClass with
  // constructorParamTypes.
  // This method assumes that there is a constructor whose parameters match the
  // types of the arguments.
  public < T extends Event > ElaborationRule
      addElaborationRule( Expression< Boolean > condition,
                          Parameter< ? > enclosingInstance,
                          Class< T > eventClass,
                          String eventName,
                          Expression<?>[] arguments ) {
    
    // Now create the EventInvocation from the constructor and arguments. 
    Vector<EventInvocation> invocation = new Vector<EventInvocation>();
    EventInvocation newInvocation = null;
    newInvocation =
        new EventInvocation( eventClass, eventName,
                             enclosingInstance, arguments,
                             (Map< String, Object >)null );
    invocation.add(newInvocation);
    Vector<Event> eventVector = new Vector<Event>();
    ElaborationRule elaborationRule = new ElaborationRule(condition, invocation);
    elaborations.put(elaborationRule, eventVector);
    return elaborationRule;
  }

  public < T extends Event > ElaborationRule
  addElaborationRule( Expression< Boolean > condition,
                      Object enclosingInstance,
                      Class< T > eventClass,
                      String eventName,
                      Expression<?>[] arguments ) {
    return addElaborationRule( condition,
                               new Parameter< Object >( "", null,
                                                        enclosingInstance, null ),
                               eventClass, eventName, arguments );
  }
  
  /* (non-Javadoc)
   * @see event.Event#addEffect(event.TimeVarying, java.lang.reflect.Method, java.util.Vector)
   */
  @Override
  public void addEffect( Parameter< ? > sv,
                         Object obj, Method effectFunction,
                         Vector< Object > arguments ) {
    assert sv != null;
    Effect e = new EffectFunction( obj, effectFunction, arguments );
    addEffect( sv, e );
  }
  
  public void addEffect( Parameter< ? > sv, Effect e ) {
    Set< Effect > effectSet = null;
    //Pair< Parameter< ? >, Set< Effect >> p = null;
    for ( Pair< Parameter< ? >, Set< Effect > > pp : effects ) {
      if ( Utils.valuesEqual( pp.first.getValue(), sv.getValue() ) ) {
        //p = pp;
        effectSet = pp.second;
        break;
      }
    }
//    if ( p != null ) {
////    if ( effects.containsKey( sv ) ) {
//      effectSet = p.second; //effects.get( sv );
//    }
    if ( effectSet == null ) {
      effectSet = new TreeSet< Effect >();
      effects.add( new Pair< Parameter< ? >, Set< Effect > >( sv, effectSet ) );
    }
    effectSet.add( e );
  }

  public void addEffects( Parameter< ? > sv, Set<Effect> set ) {
    Set< Effect > effectSet = null;
    for ( Pair< Parameter< ? >, Set< Effect > > pp : effects ) {
      if ( Utils.valuesEqual( pp.first.getValue(), sv.getValue() ) ) {
        effectSet = pp.second;
        break;
      }
    }
    if ( effectSet == null ) {
      effectSet = new TreeSet< Effect >();//set;
      effects.add( new Pair< Parameter< ? >, Set< Effect > >( sv, effectSet ) );
    }
    if ( set != null ) {
      effectSet.addAll( set );
    }
    if ( Debug.isOn() ) {
      for ( Pair< Parameter< ? >, Set< Effect > > pp : effects ) {
        if ( Utils.valuesEqual( pp.first.getValue(), sv.getValue() ) ) {
          effectSet = pp.second;
          for ( Effect effect : effectSet ) {
            if ( effect instanceof EffectFunction ) {
              EffectFunction ef = (EffectFunction)effect;
              if ( ef.object != null && !pp.first.equals( ef.object ) ) {
                System.err.println( "Error! effect variable ("
                                    + pp.first
                                    + ") does not match that of EffectFunction! ("
                                    + ef + ")" );
              }
            }
          }
        }
      }
    }
  }

  /* (non-Javadoc)
   * @see event.Event#addEffect(event.TimeVarying, java.lang.Object, java.lang.reflect.Method, java.lang.Object)
   */
  @Override
  public void addEffect( Parameter< ? > sv,
                         Object obj, Method method, Object arg ) {
    Vector< Object > v = new Vector< Object >();
    v.add( arg );
    addEffect( sv, obj, method, v );
  }

  @Override
  public Set< TimeVarying< ? > > getTimeVaryingObjects( boolean deep,
                                                        Set<HasTimeVaryingObjects> seen ) {
    Pair< Boolean, Set< HasTimeVaryingObjects > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    if ( seen != null ) seen.remove( this );
    Set< TimeVarying< ? > > set = super.getTimeVaryingObjects( deep, seen );
    //Set< TimeVarying< ? > > set = new TreeSet< TimeVarying< ? > >();
    set = Utils.addAll( set, HasTimeVaryingObjects.Helper.getTimeVaryingObjects( effects, deep, seen ) );
    if ( deep ) {
      set = Utils.addAll( set, HasTimeVaryingObjects.Helper.getTimeVaryingObjects( elaborationsConstraint, deep, seen ) );
      set = Utils.addAll( set, HasTimeVaryingObjects.Helper.getTimeVaryingObjects( effectsConstraint, deep, seen ) );
      set = Utils.addAll( set, HasTimeVaryingObjects.Helper.getTimeVaryingObjects( elaborations, deep, seen ) );
      for ( Event e : getEvents(deep, null) ) {
        if ( e instanceof HasTimeVaryingObjects ) {
          set = Utils.addAll( set, ((HasTimeVaryingObjects)e).getTimeVaryingObjects( deep, seen ) );
        }
      }
    }
    return set;
  }
  
  /* 
   * Gather any parameter instances contained by this event.
   * (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#getParameters(boolean, java.util.Set)
   */
  @Override
  public Set< Parameter< ? > > getParameters( boolean deep,
                                              Set<HasParameters> seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    if ( seen != null ) seen.remove( this );
    Set< Parameter< ? > > set = super.getParameters( deep, seen );
    if ( deep ) {
      set = Utils.addAll( set, HasParameters.Helper.getParameters( elaborationsConstraint, deep, seen ) );
      set = Utils.addAll( set, HasParameters.Helper.getParameters( effectsConstraint, deep, seen ) );
      set = Utils.addAll( set, HasParameters.Helper.getParameters( elaborations, deep, seen ) );
      set = Utils.addAll( set, HasParameters.Helper.getParameters( effects, deep, seen ) );
      for ( Event e : getEvents(deep, null) ) {
        if ( e instanceof HasParameters ) {
          set = Utils.addAll( set, ((HasParameters)e).getParameters( deep, seen ) );
        }
      }
    }
    return set;
  }

  @Override
  protected boolean tryToSatisfy( boolean deep, Set< Satisfiable > seen ) {
    boolean satisfied = super.tryToSatisfy( deep, seen );
    // REVIEW -- Is it necessary to call satisfyElaborations given the
    // elaborationsConstraint?
    if ( satisfied ) {
      satisfied = satisfyElaborations();
      if ( Debug.isOn() ) Debug.outln( this.getClass().getName() + " satisfy loop called satisfyElaborations() " );
    }
    return satisfied;
  }
  
  public Collection< Constraint > getConstraints( boolean deep,
                                                  Set<HasConstraints> seen ) {
    boolean mayHaveBeenPropagating = Parameter.mayPropagate; 
    Parameter.mayPropagate = false;
    boolean mayHaveBeenChanging = Parameter.mayChange; 
    Parameter.mayChange = false;
    Pair< Boolean, Set< HasConstraints > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) {
      Parameter.mayPropagate = mayHaveBeenPropagating;
      Parameter.mayChange = mayHaveBeenChanging;
      return Utils.getEmptySet();
    }
    seen = pair.second;
    if ( seen != null ) seen.remove( this );
    Collection< Constraint > set = new TreeSet<Constraint>();
    set = Utils.addAll( set, super.getConstraints( deep, seen ) );
    //if ( set.equals( Utils.getEmptySet() ) ) return set;
    set.add( elaborationsConstraint );
    set.add( effectsConstraint );
    if ( deep ) {
      set = Utils.addAll( set, HasConstraints.Helper.getConstraints( elaborationsConstraint, deep, seen ) );
      set = Utils.addAll( set, HasConstraints.Helper.getConstraints( effectsConstraint, deep, seen ) );
      set = Utils.addAll( set, HasConstraints.Helper.getConstraints( elaborations, deep, seen ) );
      set = Utils.addAll( set, HasConstraints.Helper.getConstraints( effects, deep, seen ) );
      Set< Event > events = getEvents( deep, null );
      set = Utils.addAll( set, HasConstraints.Helper.getConstraints( events, deep, seen ) );
    }
    Parameter.mayPropagate = mayHaveBeenPropagating;
    Parameter.mayChange = mayHaveBeenChanging;
    return set;
  }

  /*
   * Gather any event instances contained by this event.
   * 
   * NOTE: Uses reflection to dig events out of members, but does not look in
   * arrays or collections other than elaborations, so this may need
   * redefinition in subclasses. This enforces the idea that events do not occur
   * unless elaborated.
   * 
   * (non-Javadoc)
   * 
   * @see gov.nasa.jpl.ae.event.HasEvents#getEvents(boolean)
   */
  @Override
  public Set< Event > getEvents( boolean deep, Set< HasEvents > seen ) {
    Pair< Boolean, Set< HasEvents > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    Set< Event > set = new TreeSet< Event >();
    for ( Entry< ElaborationRule, Vector< Event > > e :
          elaborations.entrySet() ) {
      for ( Event event : e.getValue() ) {
        set.add( event );
        if ( deep ) {
          if ( event instanceof HasEvents )
          set = Utils.addAll( set, ((HasEvents)event).getEvents( deep, seen ) );
        }
      }
    }
    return set;
  }

  // Conditionally create child event instances from this event instance.
  /* (non-Javadoc)
   * @see event.Event#elaborate(boolean)
   */
  @Override
  public void elaborate( boolean force ) {
    if ( elaborations == null ) {
      elaborations = new TreeMap< ElaborationRule, Vector< Event > >();
    }
    for ( Entry< ElaborationRule, Vector< Event > > er : elaborations.entrySet() ) {
      if ( Debug.isOn() ) Debug.outln( getName() + " trying to elaborate " + er );
      elaborate( er, force );
    }
  }

  /**
   * @param entry
   * @return
   */
  protected boolean isElaborated( Entry< ElaborationRule, Vector< Event > > entry ) {
    return isElaborated( entry.getValue() );
  }
  
  /**
   * @param events
   * @return
   */
  protected boolean isElaborated( Vector< Event > events ) {
    boolean r = !Utils.isNullOrEmpty( events );
    if ( this.getName().equals( "fork_ForkNode_CustomerCB" )) {
      Debug.out( "" );
    }
    if ( Debug.isOn() ) Debug.outln( "isElaborated(" + events + ") = " + r  + " for " + this.getName() );
    return r;
  }
  
  /**
   * @param entry
   * @param force
   * @return
   */
  public boolean elaborate( Entry< ElaborationRule, Vector< Event > > entry,
                            boolean force ) {
    boolean elaborated = true;
    if ( !isElaborated( entry ) || force ) {

      // Don't elaborate outside the horizon.  Need startTime grounded to know.
      if ( !startTime.isGrounded(false, null) ) return false;
      if ( startTime.getValue() >= Timepoint.getHorizonDuration() ) {
        if ( Debug.isOn() ) Debug.outln( "satisfyElaborations(): No need to elaborate event outside the horizon: "
                     + getName() );
        return true;
      }

      Vector< Event > oldEvents = entry.getValue(); 
      elaborated = entry.getKey().attemptElaboration( oldEvents, true );
    }
    return elaborated;
  }
  
  /*
   * Try to remove others' references to this, possibly because it is being
   * deleted.
   * (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#detach()
   */
  @Override
  public void detach() {
    if ( Debug.isOn() ) Debug.outln( "Detaching event: " + this );
    // TODO -- REVIEW -- detach elaborations? (i.e. detach elaborated events?)

    // Detach effects.
    for ( Pair< Parameter< ? >, Set< Effect > > p : effects ) {
      Parameter< ? > tvp = p.first;
      if ( tvp == null ) return;
      if ( tvp.getValueNoPropagate() == null ) return;
      Set< Effect > set = p.second;
      if ( set == null ) return;
      TimeVarying<?> tv = Expression.evaluate( (Object)tvp, TimeVarying.class, false );
      for ( Effect e : set ) {
        // TODO -- Has unApplyTo() been implemented?
        e.unApplyTo( tv );
      }
    }

    // Remove references to time parameters from TimeVaryingMaps.
    // TODO -- REVIEW -- Is this already being done by ParameterListenerImpl.detach()
    // and TimeVaryingMap.detach( parameter )?
    Set<Timepoint> timepoints = getTimepoints( false, null );
    for ( TimeVarying< ? > tv : getTimeVaryingObjects( true, null ) ) {
      if ( tv instanceof TimeVaryingMap ) {
        ( (TimeVaryingMap<?>)tv ).keySet().removeAll( timepoints );
        ( (TimeVaryingMap<?>)tv ).isConsistent();
      }
    }
    
    super.detach();
  }
  
  @Override
  public void detach( Parameter< ? > parameter ) {
    super.detach( parameter );
    // TODO - REVIEW - remove effects referencing parameter (does this make sense?)
    for ( Pair< Parameter< ? >, Set< Effect > > p : effects ) {
      Parameter< ? > tvp = p.first;
      ArrayList< Effect > set = new ArrayList< Effect >( p.second );
      for ( Effect e : set ) {
        if ( e instanceof EffectFunction ) {
          EffectFunction ef = (EffectFunction)e;
          if ( ef.hasParameter( parameter, false, null ) ) {
            effects.remove( e );
          }
        }
      }
    }
    
    // TODO - REVIEW - remove elaborations referencing parameter (does this make sense?)
    
    // See if other events need to detach the parameter
    for ( Event e : getEvents( false, null ) ) {
      if ( e instanceof ParameterListener ) {
        ((ParameterListener)e).detach( parameter );
      }
    }
  }


  /* (non-Javadoc)
   * @see event.Event#executionToString()
   */
  @Override
  public String executionToString() {
    StringBuffer sb = new StringBuffer();
    Set< Event > events = new TreeSet< Event >();
    events.add( this );
    events = Utils.addAll( events, getEvents( true, null ) );
    for ( Event e : events ) {
      sb.append( e.toString() + "\n" );
    }
    for ( TimeVarying<?> tv : getTimeVaryingObjects( true, null ) ) {
      sb.append( tv.toString() + "\n" );
    }
    return sb.toString();
  }

 // getters and setters

  /* (non-Javadoc)
   * @see event.Event#getStartTime()
   */
  @Override
  public Timepoint getStartTime() {
    return startTime;
  }

  /* (non-Javadoc)
   * @see event.Event#setStartTime(event.Timepoint)
   */
  @Override
  public void setStartTime( Timepoint startTime ) {
    this.startTime = startTime;
  }

  /* (non-Javadoc)
   * @see event.Event#getDuration()
   */
  @Override
  public Duration getDuration() {
    return duration;
  }

  /* (non-Javadoc)
   * @see event.Event#setDuration(event.Duration)
   */
  @Override
  public void setDuration( Duration duration ) {
    this.duration = duration;
  }

  /* (non-Javadoc)
   * @see event.Event#getEndTime()
   */
  @Override
  public Timepoint getEndTime() {
    return endTime;
  }

  /* (non-Javadoc)
   * @see event.Event#setEndTime(event.Timepoint)
   */
  @Override
  public void setEndTime( Timepoint endTime ) {
    this.endTime = endTime;
  }

  /* (non-Javadoc)
   * @see event.Event#getEffects()
   */
  @Override
  public List< Pair< Parameter< ? >, Set< Effect > > > getEffects() {
    return effects;
  }

  /* (non-Javadoc)
   * @see event.Event#setEffects(java.util.SortedMap)
   */
  @Override
  public void setEffects( List< Pair< Parameter< ? >, Set< Effect > > > effects ) {
    this.effects = effects;
  }

  /* (non-Javadoc)
   * @see event.Event#getElaborations()
   */
  @Override
  public Map< ElaborationRule, Vector< Event >> getElaborations() {
    return elaborations;
  }

  /* (non-Javadoc)
   * @see event.Event#setElaborations(java.util.Map)
   */
  @Override
  public void setElaborations( Map< ElaborationRule, 
                               Vector< Event > > elaborations ) {
    this.elaborations = elaborations;
  }

  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#compareTo(gov.nasa.jpl.ae.event.ParameterListenerImpl)
   */
  @Override
  public int compareTo( ParameterListenerImpl o ) {
    if ( this == o ) return 0;
    if ( o == null ) return -1;
    int compare = super.compareTo( o );
    if ( compare != 0 ) return compare;
//    compare = Utils.compareTo( getClass().getName(), o.getClass().getName() );
//    if ( compare != 0 ) return compare;
//    compare = Utils.compareTo( getName(), o.getName() );
//    if ( compare != 0 ) return compare;
    if ( o instanceof DurativeEvent ) {
      DurativeEvent oe = (DurativeEvent)o;
      compare = startTime.compareTo( oe.getStartTime() );
      if ( compare != 0 ) return compare;
      compare = endTime.compareTo( oe.getEndTime() );
      if ( compare != 0 ) return compare;
      compare = CompareUtils.compareCollections( effects, oe.effects, true );
      if ( compare != 0 ) return compare;
      compare = CompareUtils.compareCollections( elaborations, oe.elaborations, true );
      if ( compare != 0 ) return compare;
    }
    compare = CompareUtils.compareCollections( parameters, o.getParameters(), true );
    if ( compare != 0 ) return compare;
    compare = CompareUtils.compareTo( this, o, false );
    if ( compare != 0 ) return compare;
    return compare;
  }

  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#handleValueChangeEvent(gov.nasa.jpl.ae.event.Parameter)
   * 
   * An event owns all of its parameters because it is required to contain any
   * dependency that sets one of its parameters and has some connection through
   * its other members to any reference or constraint on it. Thus, an event must
   * know about the parent event from which it is elaborated if the parent
   * references the parameter.
   * handleValueChangeEvent( parameter, newValue ) updates dependencies,
   * effects, and elaborations for the changed parameter.
   */
  @Override
  public void handleValueChangeEvent( Parameter< ? > parameter ) {
    // REVIEW -- Should we be passing in a set of parameters? Find review/todo
    // note on staleness table.

    // The super class updates the dependencies.
    super.handleValueChangeEvent( parameter );

    // Update other events
    for ( Event e : getEvents( false, null ) ) {
      if ( e instanceof ParameterListener ) {
        ((ParameterListener)e).handleValueChangeEvent( parameter );
      }
    }
    
//    // Update elaborations.
//    for ( Entry< ElaborationRule, Vector< Event > > entry : 
//          getElaborations().entrySet() ) {
//      //entry.getKey().handleValueChangeEvent( parameter );
//
//      // Initialize some local variables.
//      Vector< Event > elaboratedEvents = entry.getValue();  
//      ElaborationRule elaborationRule = entry.getKey();
//      
//      boolean elaborated = 
//          elaborationRule.attemptElaboration( elaboratedEvents, false );
//      
//      // Make sure that elaborated events have handled parameter change.
//      if ( elaborated ) { // conditionSatisfied == true here
//        assert( elaborationRule.getEventInvocations().size() ==
//                elaboratedEvents.size() );
//        for ( int i=0; i < elaboratedEvents.size(); ++i ) {
//          EventInvocation inv = elaborationRule.getEventInvocations().get( i );
//          if ( inv.getParameters( true, null ).contains( parameter ) ) {
//            Event event = elaboratedEvents.get( i );
//            if ( event instanceof ParameterListener ) {
//              ((ParameterListener)event).handleValueChangeEvent( parameter );
//            }
//          }
//        }
//      }
//    }
  }

  /**
   * @return whether or not the constraints of the elaborations have been
   *         satisfied.
   */
  public boolean satisfyElaborations() {
    // REVIEW -- code below is replicated in elaborate() and
    // elaborationsConstraint.
    // Don't elaborate outside the horizon.  Need startTime grounded to know.
    boolean deep = true;
    Set< Groundable > seenGroundable = null;
    Set< Satisfiable > seenSatisfiable = null;
    if ( !startTime.isGrounded(deep, seenGroundable) ) startTime.ground(deep, seenGroundable);
    if ( !startTime.isGrounded(deep, seenGroundable) ) return false;
    if ( startTime.getValue() >= Timepoint.getHorizonDuration() ) {
      if ( Debug.isOn() ) Debug.outln( "satisfyElaborations(): No need to elaborate event outside the horizon: "
                   + getName() );
      return true;
    }
    
    boolean satisfied = true;
    elaborate( true );
    // REVIEW -- This vector doesn't necessarily avoid concurrent modification
    // errors. Consider changing elaborations from a map into a list of pairs,
    // like parameters and effects.
    Vector< Vector< Event > > elaboratedEvents = new Vector< Vector<Event> >();
    for ( Vector< Event > v : elaborations.values() ) {
      elaboratedEvents.add( new Vector< Event >( v ) );
    }
    for ( Vector< Event > v : elaboratedEvents ) {
      for ( Event e : v ) {
        if ( e instanceof Satisfiable ) {
          if ( !( (Satisfiable)e ).isSatisfied(deep, seenSatisfiable) ) {
            if ( !( (Satisfiable)e ).satisfy(deep, seenSatisfiable) ) {
              satisfied = false;
            }
          }
        }
      }
    }
    return satisfied;
  }

  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#refresh(gov.nasa.jpl.ae.event.Parameter)
   */
  @Override
  public boolean refresh( Parameter< ? > parameter ) {
    boolean didRefresh = super.refresh( parameter );
    
    if ( !didRefresh ) {
      for ( Event e : getEvents( false, null ) ) {
        if ( e instanceof ParameterListener ) {
          if ( ((ParameterListener)e).refresh( parameter ) ) return true;
        }
      }
    }
    return didRefresh;
  }

  /* (non-Javadoc)
   * @see gov.nasa.jpl.ae.event.ParameterListenerImpl#setStaleAnyReferencesTo(gov.nasa.jpl.ae.event.Parameter)
   */
  @Override
  public void setStaleAnyReferencesTo( Parameter< ? > changedParameter ) {
    
    // Alert affected dependencies.
    super.setStaleAnyReferencesTo( changedParameter );

    for ( Event e : getEvents( false, null ) ) {
      if ( e instanceof ParameterListener ) {
        ((ParameterListener)e).setStaleAnyReferencesTo( changedParameter );
      }
    }
  }

}

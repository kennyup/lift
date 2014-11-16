package com.eigengo.lift.exercise

import java.util.{UUID, Date}

import akka.actor.{ActorLogging, ActorRefFactory, Props, ReceiveTimeout}
import akka.contrib.pattern.ShardRegion
import akka.persistence.{PersistentActor, SnapshotOffer}
import com.eigengo.lift.common.{AutoPassivation, Actors}
import com.eigengo.lift.exercise.ExerciseClassifier.{UnclassifiedExercise, Classify, FullyClassifiedExercise, ClassifiedExercise}
import com.eigengo.lift.exercise.UserExercises._
import com.eigengo.lift.profile.UserProfileProtocol.UserId

import scala.language.postfixOps
import scalaz.\/

/**
 * User + list of exercises companion
 */
object UserExercises {

  /** The shard name */
  val shardName = "user-exercises-shard"
  /** The props to create the actor on a node */
  val props = Props[UserExercises]
  /** Convenience lookup function */
  def lookup(implicit arf: ActorRefFactory) = Actors.shard.lookup(arf, shardName)

  /**
   * The event with processed fitness data into ``List[AccelerometerData]``
   * @param data the accelerometer data
   */
  case class UserExerciseDataProcessed(userId: UserId, sessionId: SessionId, data: AccelerometerData)
  
  /**
   * Starts the user exercise session
   * @param userId the user identity
   * @param session the session details
   */
  case class UserExerciseSessionStart(userId: UserId, session: Session)

  /**
   * Ends the user exercise session
   * @param userId the user identity
   * @param sessionId the generated session identity
   */
  case class UserExerciseSessionEnd(userId: UserId, sessionId: SessionId)


  /**
   * A single recorded exercise
   * @param name the name
   * @param intensity the intensity, if known
   */
  case class Exercise(name: ExerciseName, intensity: Option[ExerciseIntensity])

  /**
   * All user's exercises
   * @param sessions the list of exercises
   */
  case class Exercises(sessions: Map[Session, List[Exercise]]) extends AnyVal {
    def add(session: Session, exercise: Exercise): Exercises = {
      sessions.get(session) match {
        case None ⇒ copy(sessions = sessions + (session → List(exercise)))
        case Some(exercises) ⇒ copy(sessions = sessions + (session → exercises.+:(exercise)))
      }
    }
    def start(session: Session): Exercises = copy(sessions + (session → List.empty))
  }
  
  object Exercises {
    val empty: Exercises = Exercises(Map.empty)
  }
  

  /**
   * Query to receive all exercises for the given ``userId``
   * @param userId the user identity
   */
  case class UserGetAllExercises(userId: UserId)

  /**
   * Query to receive all exercises. The relationship between ``GetUserExercises`` and ``GetExercises`` is that
   * ``GetUserExercises`` is sent to the shard coordinator, which locates the appropriate (user-specific) shard,
   * and sends it the ``GetExercises`` message
   */
  private case object GetExercises

  /**
   * The session has started
   * @param session the session identity
   */
  private case class ExerciseSessionStart(session: Session)

  /**
   * The session has ended
   * @param sessionId the session identity
   */
  private case class ExerciseSessionEnd(sessionId: SessionId)

  /**
   * Accelerometer data for the given session
   * @param sessionId the session identity
   * @param data the data
   */
  private case class ExerciseSessionData(sessionId: SessionId, data: AccelerometerData)

  /**
   * Extracts the identity of the shard from the messages sent to the coordinator. We have per-user shard,
   * so our identity is ``userId.toString``
   */
  val idExtractor: ShardRegion.IdExtractor = {
    case UserExerciseSessionStart(userId, session) ⇒ (userId.toString, ExerciseSessionStart(session))
    case UserExerciseSessionEnd(userId, sessionId) ⇒ (userId.toString, ExerciseSessionEnd(sessionId))
    case UserExerciseDataProcessed(userId, sessionId, data) ⇒ (userId.toString, ExerciseSessionData(sessionId, data))
    case UserGetAllExercises(userId) ⇒ (userId.toString, GetExercises)
  }

  /**
   * Resolves the shard name from the incoming message.
   */
  val shardResolver: ShardRegion.ShardResolver = {
    case _ ⇒ "global"
  }

}

/**
 * Models each user's exercises as its state, which is updated upon receiving and classifying the
 * ``AccelerometerData``. It also provides the query for the current state.
 */
class UserExercises extends PersistentActor with ActorLogging with AutoPassivation {
  import scala.concurrent.duration._

  // minimum confidence
  private val confidenceThreshold = 0.5
  // how long until we stop processing
  override val passivationTimeout: Duration = 360.seconds
  // our unique persistenceId; the self.path.name is provided by ``UserExercises.idExtractor``,
  // hence, self.path.name is the String representation of the userId UUID.
  override val persistenceId: String = s"user-exercises-${self.path.name}"
  // our internal state
  private var exercises = Exercises.empty

  // when this actor recovers (i.e. moving from "not present" to "present"), it is sent messages that
  // we handle to get to the state that the actor was before it was removed.
  override def receiveRecover: Receive = {
    // restore from snapshot
    case SnapshotOffer(_, offeredSnapshot: Exercises) ⇒
      log.debug(s"Recovered from snapshot. Now with $exercises")
      exercises = offeredSnapshot
      
    // restart session 
    case ExerciseSessionStart(session) ⇒ exercises = exercises.start(session) 
      
    // reclassify the exercise in AccelerometerData
    case c@Classify(_, _) ⇒ ExerciseClassifiers.lookup ! c
  }

  private def exercising(session: Session): Receive = withPassivation {
    case ExerciseSessionStart(_) ⇒
      sender() ! \/.left("Session is running")

    // classify the exercise in AccelerometerData
    case ExerciseSessionData(id, data) if id == session.id ⇒
      log.debug(s"AccelerometerData ${self.path.toString}")
      persist(Classify(session, data))(ExerciseClassifiers.lookup !)

    // classification results received
    case FullyClassifiedExercise(`session`, confidence, name, intensity) ⇒
      if (confidence > confidenceThreshold) exercises = exercises.add(session, Exercise(name, intensity))
      intensity.foreach(i ⇒ if (i < session.intendedIntensity) {
        log.info("HARDER!!!")
      })
      saveSnapshot(exercises)

    case UnclassifiedExercise(`session`) ⇒
      log.debug("NOTIFY!!")

    case ExerciseSessionEnd(id) ⇒
      log.debug(s"ExerciseSessionEnded($id)")
      context.become(notExercising)
      sender() ! \/.right("ended")
      
    // query for exercises
    case GetExercises =>
      log.debug(s"GetExercises ${self.path.toString}")
      sender() ! exercises
  }

  private def notExercising: Receive = withPassivation {
    case FullyClassifiedExercise(session, confidence, name, intensity) ⇒
      if (confidence > confidenceThreshold) exercises = exercises.add(session, Exercise(name, intensity))

    case ExerciseSessionEnd(_) ⇒
      sender() ! \/.left("Session not running")

    case ExerciseSessionStart(session) ⇒
      log.debug("Start exercise session")
      exercises = exercises.start(session)
      context.become(exercising(session))
      sender() ! \/.right('OK)

    case GetExercises ⇒
      log.debug(s"GetExercises ${self.path.toString}")
      sender() ! exercises
  }

  // after recovery is complete, we move to processing commands
  override def receiveCommand: Receive = notExercising

}

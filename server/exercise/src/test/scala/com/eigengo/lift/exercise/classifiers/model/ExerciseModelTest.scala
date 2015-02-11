package com.eigengo.lift.exercise.classifiers.model

import akka.actor.{ActorSystem, ActorLogging}
import akka.stream.{ActorFlowMaterializer, ActorFlowMaterializerSettings}
import akka.stream.scaladsl._
import akka.testkit.{TestKit, TestProbe, TestActorRef}
import com.eigengo.lift.exercise._
import com.eigengo.lift.exercise.classifiers.ExerciseModel
import com.eigengo.lift.exercise.classifiers.workflows.ClassificationAssertions
import com.typesafe.config.ConfigFactory
import java.text.SimpleDateFormat
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Gen._
import org.scalatest._
import org.scalatest.prop._

class ExerciseModelTest
  extends TestKit(ActorSystem("TestSystem", ConfigFactory.load("test.conf").withFallback(ConfigFactory.load("classification.conf"))))
  with PropSpecLike
  with PropertyChecks
  with Matchers
  with ExerciseGenerators {

  import ClassificationAssertions._
  import ExerciseModel._

  val settings = ActorFlowMaterializerSettings(system).withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = ActorFlowMaterializer(settings)

  val defaultDepth = 3

  val SensorQueryGen: Gen[SensorDataSourceLocation] =
    Gen.oneOf(SensorDataSourceLocationWrist, SensorDataSourceLocationWaist, SensorDataSourceLocationFoot, SensorDataSourceLocationChest, SensorDataSourceLocationAny)

  val FactGen: Gen[Fact] = frequency(
    1 -> Gen.oneOf(True, False),
    1 -> (for { name <- arbitrary[String]; matchProbability <- arbitrary[Double] } yield Gesture(name, matchProbability)),
    1 -> (for { name <- arbitrary[String]; matchProbability <- arbitrary[Double] } yield NegGesture(name, matchProbability))
  )

  def PropositionGen(depth: Int = defaultDepth): Gen[Proposition] = frequency(
    5 -> (for { sensor <- Gen.lzy(SensorQueryGen); fact <- Gen.lzy(FactGen) } yield Assert(sensor, fact)),
    1 -> (for { fact1 <- Gen.lzy(PropositionGen(depth-1)); fact2 <- Gen.lzy(PropositionGen(depth-1)) } yield Conjunction(fact1, fact2)),
    1 -> (for { fact1 <- Gen.lzy(PropositionGen(depth-1)); fact2 <- Gen.lzy(PropositionGen(depth-1)) } yield Disjunction(fact1, fact2))
  )

  def PathGen(depth: Int = defaultDepth): Gen[Path] = frequency(
    5 -> Gen.lzy(PropositionGen(depth-1)).map(AssertFact),
    5 -> Gen.lzy(QueryGen(depth-1)).map(Test),
    1 -> (for { path1 <- Gen.lzy(PathGen(depth-1)); path2 <- Gen.lzy(PathGen(depth-1)) } yield Choice(path1, path2)),
    1 -> (for { path1 <- Gen.lzy(PathGen(depth-1)); path2 <- Gen.lzy(PathGen(depth-1)) } yield Seq(path1, path2)),
    5 -> Gen.lzy(PathGen(depth-1)).map(Repeat)
  )

  def QueryGen(depth: Int = defaultDepth): Gen[Query] = frequency(
    5 -> (for { sensor <- Gen.lzy(SensorQueryGen); fact <- Gen.lzy(PropositionGen(depth-1)) } yield Formula(fact)),
    5 -> Gen.const(TT),
    5 -> Gen.const(FF),
    1 -> (for { query1 <- Gen.lzy(QueryGen(depth-1)); query2 <- Gen.lzy(QueryGen(depth-1)) } yield And(query1, query2)),
    1 -> (for { query1 <- Gen.lzy(QueryGen(depth-1)); query2 <- Gen.lzy(QueryGen(depth-1)) } yield Or(query1, query2)),
    5 -> (for { path <- Gen.lzy(PathGen(depth-1)); query <- Gen.lzy(QueryGen(depth-1)) } yield Exists(path, query)),
    5 -> (for { path <- Gen.lzy(PathGen(depth-1)); query <- Gen.lzy(QueryGen(depth-1)) } yield All(path, query))
  )

  val QueryValueGen: Gen[QueryValue] = frequency(
    1 -> arbitrary[Boolean].map(StableValue),
    1 -> (for { result <- arbitrary[Boolean]; query <- QueryGen() } yield UnstableValue(result, query))
  )

  property("meet(complement(x), complement(y)) == complement(join(x, y))") {
    forAll(QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue) =>
      meet(complement(value1), complement(value2)) === complement(join(value1, value2))
    }
  }

  property("complement(complement(x)) == x") {
    forAll(QueryValueGen) { (value: QueryValue) =>
      complement(complement(value)) === value
    }
  }

  property("meet(x, y) == meet(y, x)") {
    forAll(QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue) =>
      meet(value1, value2) === meet(value2, value1)
    }
  }

  property("join(x, y) == join(y, x)") {
    forAll(QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue) =>
      join(value1, value2) === join(value2, value1)
    }
  }

  property("meet(x, meet(y, z)) == meet(meet(x, y), z)") {
    forAll(QueryValueGen, QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue, value3: QueryValue) =>
      meet(value1, meet(value2, value3)) === meet(meet(value1, value2), value3)
    }
  }

  property("join(x, join(y, z)) == join(join(x, y), z)") {
    forAll(QueryValueGen, QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue, value3: QueryValue) =>
      join(value1, join(value2, value3)) === join(join(value1, value2), value3)
    }
  }

  property("join(x, meet(y, z)) == meet(join(x, y), join(x, z))") {
    forAll(QueryValueGen, QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue, value3: QueryValue) =>
      join(value1, meet(value2, value3)) === meet(join(value1, value2), join(value1, value3))
    }
  }

  property("meet(x, join(y, z)) == join(meet(x, y), meet(x, z))") {
    forAll(QueryValueGen, QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue, value3: QueryValue) =>
      meet(value1, join(value2, value3)) === join(meet(value1, value2), meet(value1, value3))
    }
  }

  property("join(x, meet(x, y)) == x") {
    forAll(QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue) =>
      join(value1, meet(value1, value2)) === value1
    }
  }

  property("meet(x, join(x, y)) == x") {
    forAll(QueryValueGen, QueryValueGen) { (value1: QueryValue, value2: QueryValue) =>
      meet(value1, join(value1, value2)) === value1
    }
  }

  property("meet(x, x) == x") {
    forAll(QueryValueGen) { (value: QueryValue) =>
      meet(value, value) === value
    }
  }

  property("join(x, x) == x") {
    forAll(QueryValueGen) { (value: QueryValue) =>
      join(value, value) === value
    }
  }

  property("not(not(x)) == x") {
    forAll(QueryGen()) { (query: Query) =>
      ExerciseModel.not(ExerciseModel.not(query)) === query
    }
  }

  property("ExerciseModel should correctly 'slice up' SensorNet messages into SensorValue events") {
    val rate = system.settings.config.getInt("classification.frequency")
    val modelProbe = TestProbe()
    val model = TestActorRef(new ExerciseModel with ActorLogging {
      val name = "test"
      val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
      val startDate = dateFormat.parse("1970-01-01")
      val sessionProps = SessionProperties(startDate, scala.Seq("Legs"), 1.0)
      val positiveWatch = Set.empty[Query]
      val negativeWatch = Set.empty[Query]
      val workflow = Flow[SensorNetValue].map(snv => new BindToSensors(Set(), Set(), Set(), Set(), Set(), snv))
      def evaluate(formula: Query)(current: BindToSensors, lastState: Boolean) = StableValue(result = true)
      override def aroundReceive(receive: Receive, msg: Any) = msg match {
        case value: SensorNetValue =>
          modelProbe.ref ! value

        case _ =>
          super.aroundReceive(receive, msg)
      }
    })

    forAll(SensorNetGen(30)) { (rawEvent: SensorNet) =>
      val event = SensorNet(rawEvent.toMap.mapValues(evt => new SensorData { val samplingRate = rate; val values = evt.values }))

      model ! event

      val msgs = modelProbe.receiveN(event.wrist.values.length).asInstanceOf[Vector[SensorNetValue]].toList
      for (sensor <- Sensor.sourceLocations) {
        assert(msgs.map(_.toMap(sensor)) == event.toMap(sensor).values)
      }
    }
  }

}

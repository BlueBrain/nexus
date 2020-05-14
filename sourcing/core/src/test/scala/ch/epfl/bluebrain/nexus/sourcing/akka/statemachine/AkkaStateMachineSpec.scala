package ch.epfl.bluebrain.nexus.sourcing.akka.statemachine

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, NotInfluenceReceiveTimeout}
import akka.cluster.Cluster
import akka.testkit._
import akka.util.Timeout
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.Command._
import ch.epfl.bluebrain.nexus.sourcing.State.{Current, Initial}
import ch.epfl.bluebrain.nexus.sourcing.StateMachineFixture._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.akka.Msg._
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig.AkkaStateMachineConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Random

class AkkaStateMachineSpec
    extends TestKit(ActorSystem("AkkaStateMachineSpec"))
    with SourcingSpec
    with BeforeAndAfterAll
    with ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(2.second.dilated, 50.milliseconds)

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val ec: ExecutionContext  = system.dispatcher
  implicit val timer: Timer[IO]      = IO.timer(ec)

  val config = AkkaStateMachineConfig(
    Timeout(1.second.dilated),
    200.milliseconds,
    ExecutionContext.global,
    influenceInvalidationOnGet = true
  )

  val neverStrategy: RetryStrategyConfig = RetryStrategyConfig("never", 0.seconds, 0.seconds, 0, 0.seconds)

  "A sharded AkkaStateMachine" when {

    "configured with immediate invalidation and no retries" should {
      val invalidation   = StopStrategy.immediately[State, Command]
      implicit val retry = neverStrategy.retryPolicy[IO]
      val name           = "immediate-invalidation-no-retries"
      val cache = AkkaStateMachine
        .sharded[IO](name, initialState, evaluate[IO], invalidation, config, shards = 10)
        .unsafeRunSync()

      val id = genString()

      "update its state and invalidate immediately when accepting commands" in {
        cache.evaluate(id, Increment(0, 2)).unsafeRunSync().rightValue shouldEqual Current(1, 2)
        cache.currentState(id).unsafeRunSync() shouldEqual Initial
        cache.evaluate(id, IncrementAsync(1, 5, 10.millis)).unsafeRunSync().leftValue
      }

      runErrorTests(cache, id, rev = 0)
    }

    "configured with no invalidation and no retries" should {
      val invalidation   = StopStrategy.never[State, Command]
      implicit val retry = neverStrategy.retryPolicy[IO]
      val name           = "no-invalidation-no-retries"
      val cache = AkkaStateMachine
        .sharded[IO](name, initialState, evaluate[IO], invalidation, config, shards = 10)
        .unsafeRunSync()

      val id = genString()
      runUpdateStateTests(cache, name, id)
      runErrorTests(cache, id, rev = 5)
    }

    "configured with interaction invalidation and no retries" should {
      val invalidation   = StopStrategy.lapsedSinceLastInteraction[State, Command](6.seconds.dilated)
      implicit val retry = neverStrategy.retryPolicy[IO]
      val name           = "interaction-invalidation-no-retries"
      val cache = AkkaStateMachine
        .sharded[IO](name, initialState, evaluate[IO], invalidation, config, shards = 10)
        .unsafeRunSync()

      val id = genString()
      runUpdateStateTests(cache, name, id)
      runErrorTests(cache, id, rev = 5)
    }

    "configured with interaction invalidation ignored and no retries" should {
      val invalidation            = StopStrategy.lapsedSinceLastInteraction[State, Command](100.milliseconds)
      implicit val retry          = neverStrategy.retryPolicy[IO]
      val name                    = "interaction-ignored-invalidation-no-retries"
      val configNoInvalidateOnGet = config.copy(influenceInvalidationOnGet = false)
      val cache = AkkaStateMachine
        .sharded[IO](name, initialState, evaluate[IO], invalidation, configNoInvalidateOnGet, shards = 10)
        .unsafeRunSync()

      val id = genString()

      "update its state when accepting commands" in {
        val increment  = new Increment(0, 2) with NotInfluenceReceiveTimeout
        val increment2 = new Increment(1, 5) with NotInfluenceReceiveTimeout
        cache.evaluate(id, increment).unsafeRunSync().rightValue shouldEqual Current(1, 2)
        Thread.sleep(50)
        cache.currentState(id).unsafeRunSync() shouldEqual Current(1, 2)
        cache.evaluate(id, increment2).unsafeRunSync().rightValue shouldEqual Current(2, 7)
        Thread.sleep(70)
        cache.currentState(id).unsafeRunSync() shouldEqual Initial
        cache.evaluate(id, increment).unsafeRunSync().rightValue shouldEqual Current(1, 2)
      }
    }

    "configured with a retry strategy" should {
      def eval(failCount: Int) = {
        val evaluations = new AtomicInteger(0)
        val f = (state: State, cmd: Command) => {
          if (evaluations.get() < failCount)
            IO.pure(evaluations.incrementAndGet()) >> IO.raiseError(new RuntimeException)
          else IO.pure(evaluations.incrementAndGet()) >> evaluate[IO](state, cmd)
        }
        (evaluations, f)
      }

      "retry the computation once, resulting in success" in {
        val (evaluations, f) = eval(1)
        val invalidation     = StopStrategy.never[State, Command]
        val name             = "no-invalidation-single-retry-success"
        implicit val retry   = neverStrategy.copy(strategy = "once", initialDelay = 10.millis).retryPolicy[IO]
        val cache = AkkaStateMachine
          .sharded[IO](name, initialState, f, invalidation, config, shards = 10)
          .unsafeRunSync()

        val id = genString()

        cache.evaluate(id, Increment(0, 2)).unsafeRunSync().rightValue
        evaluations.get() shouldEqual 2
        cache.currentState(id)
      }

      "retry the computation once, resulting in failure" in {
        val (evaluations, f) = eval(100)
        val invalidation     = StopStrategy.never[State, Command]
        val name             = "no-invalidation-single-retry-failure"
        implicit val retry   = neverStrategy.copy(strategy = "once", initialDelay = 10.millis).retryPolicy[IO]
        val cache = AkkaStateMachine
          .sharded[IO](name, initialState, f, invalidation, config, shards = 10)
          .unsafeRunSync()

        val id = genString()

        val ex = intercept[CommandEvaluationError[Command]] {
          cache.evaluate(id, Increment(0, 2)).unsafeRunSync()
        }
        ex shouldEqual CommandEvaluationError(id, Increment(0, 2), None)
        evaluations.get() shouldEqual 2
      }

      "retry the computation exponentially, resulting in failure" in {
        val (evaluations, f) = eval(100)
        val invalidation     = StopStrategy.never[State, Command]
        val name             = "no-invalidation-exponential-retry-failure"
        implicit val retry   = RetryStrategyConfig("exponential", 10.millis, 10.seconds, 3, 1.second).retryPolicy[IO]
        val cache = AkkaStateMachine
          .sharded[IO](name, initialState, f, invalidation, config, shards = 10)
          .unsafeRunSync()

        val id = genString()

        val ex = intercept[CommandEvaluationError[Command]] {
          cache.evaluate(id, Increment(0, 2)).unsafeRunSync()
        }

        ex shouldEqual CommandEvaluationError(id, Increment(0, 2), None)
        evaluations.get() shouldEqual 4
      }
    }
  }

  "A tree AkkaStateMachine" when {

    "configured with immediate invalidation and no retries" should {
      val invalidation   = StopStrategy.immediately[State, Command]
      implicit val retry = neverStrategy.retryPolicy[IO]
      val name           = "immediate-invalidation-no-retries"
      val cache = AkkaStateMachine
        .tree[IO](name, initialState, evaluate[IO], invalidation, config, poolSize = 10)
        .unsafeRunSync()

      val id = genString()

      "update its state and invalidate immediately when accepting commands" in {
        cache.evaluate(id, Increment(0, 2)).unsafeRunSync().rightValue shouldEqual Current(1, 2)
        cache.evaluate(id, IncrementAsync(1, 5, 10.millis)).unsafeRunSync().leftValue
        cache.currentState(id).unsafeRunSync() shouldEqual Initial
      }

      runErrorTests(cache, id, rev = 0)

    }

    "configured with no invalidation and no retries" should {
      val invalidation   = StopStrategy.never[State, Command]
      implicit val retry = neverStrategy.retryPolicy[IO]
      val name           = "no-invalidation-no-retries"
      val cache = AkkaStateMachine
        .tree[IO](name, initialState, evaluate[IO], invalidation, config, poolSize = 10)
        .unsafeRunSync()

      val id = genString()
      runUpdateStateTests(cache, name, id)
      runErrorTests(cache, id, rev = 5)
    }

    "configured with interaction invalidation and no retries" should {
      val invalidation   = StopStrategy.lapsedSinceLastInteraction[State, Command](6.seconds.dilated)
      implicit val retry = neverStrategy.retryPolicy[IO]
      val name           = "interaction-invalidation-no-retries"
      val cache = AkkaStateMachine
        .tree[IO](name, initialState, evaluate[IO], invalidation, config, poolSize = 10)
        .unsafeRunSync()

      val id = genString()
      runUpdateStateTests(cache, name, id)
      runErrorTests(cache, id, rev = 5)
    }

    "configured with a retry strategy" should {
      def eval(failCount: Int) = {
        val evaluations = new AtomicInteger(0)
        val f = (state: State, cmd: Command) => {
          if (evaluations.get() < failCount)
            IO.pure(evaluations.incrementAndGet()) >> IO.raiseError(new RuntimeException)
          else IO.pure(evaluations.incrementAndGet()) >> evaluate[IO](state, cmd)
        }
        (evaluations, f)
      }

      "retry the computation once, resulting in success" in {
        val (evaluations, f) = eval(1)
        val invalidation     = StopStrategy.never[State, Command]
        val name             = "no-invalidation-single-retry-success"
        implicit val retry   = neverStrategy.copy(strategy = "once", initialDelay = 10.millis).retryPolicy[IO]
        val cache = AkkaStateMachine
          .tree[IO](name, initialState, f, invalidation, config, poolSize = 10)
          .unsafeRunSync()

        val first = genString()
        cache.evaluate(first, Increment(0, 2)).unsafeRunSync().rightValue
        evaluations.get() shouldEqual 2
      }

      "retry the computation once, resulting in failure" in {
        val (evaluations, f) = eval(100)
        val invalidation     = StopStrategy.never[State, Command]
        val name             = "no-invalidation-single-retry-failure"
        implicit val retry   = neverStrategy.copy(strategy = "once", initialDelay = 10.millis).retryPolicy[IO]
        val cache = AkkaStateMachine
          .tree[IO](name, initialState, f, invalidation, config, poolSize = 10)
          .unsafeRunSync()

        val first = genString()
        val ex = intercept[CommandEvaluationError[Command]] {
          cache.evaluate(first, Increment(0, 2)).unsafeRunSync()
        }
        ex shouldEqual CommandEvaluationError(first, Increment(0, 2), None)
        evaluations.get() shouldEqual 2
      }

      "retry the computation exponentially, resulting in failure" in {
        val (evaluations, f) = eval(100)
        val invalidation     = StopStrategy.never[State, Command]
        val name             = "no-invalidation-exponential-retry-failure"
        implicit val retry   = RetryStrategyConfig("exponential", 10.millis, 10.seconds, 3, 1.second).retryPolicy[IO]
        val cache = AkkaStateMachine
          .tree[IO](name, initialState, f, invalidation, config, poolSize = 10)
          .unsafeRunSync()

        val first = genString()
        val ex = intercept[CommandEvaluationError[Command]] {
          cache.evaluate(first, Increment(0, 2)).unsafeRunSync()
        }

        ex shouldEqual CommandEvaluationError(first, Increment(0, 2), None)
        evaluations.get() shouldEqual 4
      }
    }
  }

  def runUpdateStateTests(
      cache: StateMachine[IO, String, State, Command, Rejection],
      name: String,
      id: String
  ): Unit = {

    "return its name" in {
      cache.name shouldEqual name
    }

    "update its state when accepting commands" in {
      cache.evaluate(id, Increment(0, 2)).unsafeRunSync().rightValue shouldEqual Current(1, 2)
      cache.evaluate(id, IncrementAsync(1, 5, 10.millis)).unsafeRunSync().rightValue shouldEqual Current(2, 7)
      cache.currentState(id).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "test without applying changes" in {
      cache.test(id, Initialize(0)).unsafeRunSync().leftValue
      cache.test(id, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      cache.test(id, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      cache.currentState(id).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "not update its state if evaluation fails" in {
      cache.evaluate(id, Initialize(0)).unsafeRunSync().leftValue
      cache.currentState(id).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "evaluate commands one at a time" in {
      cache.evaluate(id, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      cache.currentState(id).unsafeRunSync() shouldEqual Current(3, 0)
      cache.evaluate(id, IncrementAsync(3, 2, 30.millis)).unsafeToFuture()
      cache.evaluate(id, IncrementAsync(4, 2, 10.millis)).unsafeRunSync().rightValue shouldEqual Current(5, 4)
      cache.currentState(id).unsafeRunSync() shouldEqual Current(5, 4)
    }
  }

  def runErrorTests(cache: StateMachine[IO, String, State, Command, Rejection], id: String, rev: Int): Unit = {

    "return a timeout when evaluating commands that do not complete" in {
      val ex = intercept[CommandEvaluationTimeout[Command]] {
        cache.evaluate(id, Never(rev)).unsafeRunSync()
      }
      ex shouldEqual CommandEvaluationTimeout(id, Never(rev))
    }

    "return a timeout when testing commands that do not complete" in {
      val ex = intercept[CommandEvaluationTimeout[Command]] {
        cache.test(id, Never(rev)).unsafeRunSync()
      }
      ex shouldEqual CommandEvaluationTimeout(id, Never(rev))
    }

    "return the error when evaluating commands that return in error" in {
      val ex = intercept[CommandEvaluationError[Command]] {
        cache.evaluate(id, Boom(rev, "the message")).unsafeRunSync()
      }
      ex shouldEqual CommandEvaluationError(id, Boom(rev, "the message"), Some("the message"))
    }

    "return the error when testing commands that return in error" in {
      val ex = intercept[CommandEvaluationError[Command]] {
        cache.test(id, Boom(rev, "the message")).unsafeRunSync()
      }
      ex shouldEqual CommandEvaluationError(id, Boom(rev, "the message"), Some("the message"))
    }

    "return the current state while still evaluating" in {
      val f = cache.evaluate(id, Never(rev)).unsafeToFuture()
      cache.currentState(id).unsafeRunSync()
      f.failed.futureValue
    }

    "return the current state while still testing" in {
      val f = cache.test(id, Never(rev)).unsafeToFuture()
      cache.currentState(id).unsafeRunSync()
      f.failed.futureValue
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val promise = Promise[Unit]
    Cluster(system).registerOnMemberUp(promise.success(()))
    Cluster(system).join(Cluster(system).selfAddress)
    promise.future.futureValue
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  def genString(length: Int = 16, pool: IndexedSeq[Char] = Vector.range('a', 'z')): String = {
    val size = pool.size

    @tailrec
    def inner(acc: String, remaining: Int): String =
      if (remaining <= 0) acc
      else inner(acc + pool(Random.nextInt(size)), remaining - 1)

    inner("", length)
  }
}

package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreSpec.RevisionedValue
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreSubscriber.KeyValueStoreChange.{ValueAdded, ValueModified, ValueRemoved}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreSubscriber.KeyValueStoreChanges
import ch.epfl.bluebrain.nexus.delta.sdk.cache.SubscriberCommand.Unsubscribe
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.ConfigFactory
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class KeyValueStoreSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load("cache/cache.conf"))
    with AsyncWordSpecLike
    with Matchers
    with IOValues
    with OptionValues {

  type RevisionChange = KeyValueStoreSubscriber.KeyValueStoreChange[String, RevisionedValue[String]]

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit val config: KeyValueStoreConfig =
    KeyValueStoreConfig(4.seconds, 3.seconds, RetryStrategyConfig.AlwaysGiveUp)

  private val expectedChanges: Set[RevisionChange] = Set(
    ValueAdded("a", RevisionedValue(1, "a")),
    ValueModified("a", RevisionedValue(2, "aa")),
    ValueAdded("b", RevisionedValue(1, "b")),
    ValueModified("a", RevisionedValue(3, "aac")),
    ValueRemoved("a", RevisionedValue(3, "aac"))
  )

  val changes: ListBuffer[KeyValueStoreChanges[String, RevisionedValue[String]]] = ListBuffer.empty

  val onChange: OnKeyValueStoreChange[String, RevisionedValue[String]] =
    (value: KeyValueStoreChanges[String, RevisionedValue[String]]) => IO.pure(changes += value)

  private val subscriber: ActorRef[SubscriberCommand] =
    spawn(KeyValueStoreSubscriber("spec", onChange), "subscriber")

  private val store = KeyValueStore.distributed[String, RevisionedValue[String]](
    "spec",
    { case (_, rv) => rv.rev }
  )

  "A KeyValueStore" should {
    "store values" in {
      for {
        _ <- store.put("a", RevisionedValue(1, "a"))
        a <- store.get("a")
        _ <- store.flushChanges
      } yield {
        a.value shouldEqual RevisionedValue(1, "a")
      }
    }

    "discard updates for same revisions" in {
      for {
        _ <- store.put("a", RevisionedValue(1, "b"))
        a <- store.get("a")
      } yield {
        a.value shouldEqual RevisionedValue(1, "a")
      }
    }

    "update values" in {
      for {
        _ <- store.put("a", RevisionedValue(2, "aa"))
        a <- store.get("a")
        _ <- store.flushChanges
      } yield {
        a.value shouldEqual RevisionedValue(2, "aa")
      }
    }

    "discard updates for previous revisions" in {
      for {
        _ <- store.put("a", RevisionedValue(1, "a"))
        a <- store.get("a")
      } yield {
        a.value shouldEqual RevisionedValue(2, "aa")
      }
    }

    "discard updates on present keys" in {
      for {
        p <- store.putIfAbsent("a", RevisionedValue(4, "b"))
        a <- store.get("a")
        _ <- store.flushChanges
      } yield {
        p shouldEqual false
        a.value shouldEqual RevisionedValue(2, "aa")
      }
    }

    "return all entries" in {
      for {
        p       <- store.putIfAbsent("b", RevisionedValue(1, "b"))
        entries <- store.entries
        _       <- store.flushChanges
      } yield {
        p shouldEqual true
        entries shouldEqual Map(
          "b" -> RevisionedValue(1, "b"),
          "a" -> RevisionedValue(2, "aa")
        )
      }
    }

    "return all values" in {
      store.values.map {
        _ should contain theSameElementsAs Vector(RevisionedValue(1, "b"), RevisionedValue(2, "aa"))
      }
    }

    "return a matching (key, value)" in {
      store.find({ case (k, _) => k == "a" }).map {
        _.value shouldEqual ("a" -> RevisionedValue(2, "aa"))
      }
    }

    "fail to return a matching (key, value)" in {
      store.find({ case (k, _) => k == "c" }).map {
        _ shouldEqual None
      }
    }

    "return a matching value" in {
      store.findValue(_.value == "aa").map {
        _.value shouldEqual RevisionedValue(2, "aa")
      }
    }

    "fail to return a matching value" in {
      store.findValue(_.value == "cc").map {
        _ shouldEqual None
      }
    }

    "return the matching and transformed (key, value)" in {
      store.collectFirst { case (k, RevisionedValue(2, "aa")) => k }.map {
        _.value shouldEqual "a"
      }
    }

    "fail to return the matching and transformed (key, value)" in {
      store.collectFirst { case (k, RevisionedValue(4, "aa")) => k }.map {
        _ shouldEqual None
      }
    }

    "update values computing from current value" in {
      for {
        v <- store.computeIfPresent("a", c => c.copy(c.rev + 1, c.value + "c"))
        a <- store.get("a")
        _ <- store.flushChanges
      } yield {
        v.value shouldEqual RevisionedValue(3, "aac")
        a.value shouldEqual RevisionedValue(3, "aac")
      }
    }

    "discard updates on computing value when new revision is not greater than current" in {
      for {
        _ <- store.computeIfPresent("a", c => c.copy(c.rev, c.value + "d"))
        a <- store.get("a")
      } yield {
        a.value shouldEqual RevisionedValue(3, "aac")
      }
    }

    "discard updates on computing value when key does not exist" in {
      for {
        _ <- store.computeIfPresent("c", c => c.copy(c.rev, c.value + "d"))
        c <- store.get("c")
      } yield {
        c shouldEqual None
      }
    }

    "remove a key" in {
      for {
        _       <- store.remove("a")
        entries <- store.entries
        _       <- store.flushChanges
      } yield {
        entries shouldEqual Map("b" -> RevisionedValue(1, "b"))
      }
    }

    "return empty entries" in {
      val empty = KeyValueStore.distributed[String, RevisionedValue[String]](
        "empty",
        { case (_, rv) => rv.rev }
      )

      empty.entries.runSyncUnsafe() shouldEqual Map.empty[String, RevisionedValue[String]]
    }

    "verify subscriber changes" in eventually {
      for {
        _ <- store.flushChanges
        _ <- IO {
               changes.foldLeft(Set.empty[RevisionChange]) { case (acc, change) =>
                 acc ++ change.values
               } should contain theSameElementsAs expectedChanges
             }
      } yield succeed
    }

    "unsubscribe" in {
      IO {
        val probe = createTestProbe()
        subscriber ! Unsubscribe
        probe.expectTerminated(subscriber)
        succeed
      }
    }
  }
}

object KeyValueStoreSpec {
  final case class RevisionedValue[A](rev: Long, value: A)
}

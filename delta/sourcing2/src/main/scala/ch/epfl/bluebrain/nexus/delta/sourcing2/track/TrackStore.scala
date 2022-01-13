package ch.epfl.bluebrain.nexus.delta.sourcing2.track

import cats.data.NonEmptySet
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing2.Transactors
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import doobie._
import doobie.implicits._
import monix.bio.{Task, UIO}

import java.util.concurrent.TimeUnit
import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters._

trait TrackStore {

  def select(track: Track): Task[SelectedTrack] =
    track match {
      case Track.All           =>
        Task.pure(SelectedTrack.All)
      case Track.Single(value) =>
        fetch(value).map(_.fold[SelectedTrack](SelectedTrack.NotFound)(SelectedTrack.Single))
    }

  def fetch(track: String): Task[Option[Int]]

  def getOrCreate(tracks: Set[String]): Task[Map[String, Int]]

}

object TrackStore {

  final private[track] class TrackStoreImpl(config: TrackConfig, rwt: Transactors) extends TrackStore {

    private val cache: Cache[String, Int] = Caffeine
      .newBuilder()
      .expireAfterAccess(config.cacheTtl.toSeconds, TimeUnit.SECONDS)
      .maximumSize(config.maxSize)
      .build[String, Int]()

    override def fetch(track: String): Task[Option[Int]] = {
      for {
        cached  <- UIO.delay(Option(cache.getIfPresent(track)))
        fetched <-
          cached.fold(sql"SELECT id FROM tracks where name = $track".query[Int].option.transact(rwt.read))(_ =>
            Task.none[Int]
          )
        _       <- fetched.fold(UIO.unit)(id => UIO.delay(cache.put(track, id)))
      } yield cached.orElse(fetched)
    }

    override def getOrCreate(tracks: Set[String]): Task[Map[String, Int]] = {
      val insert = "INSERT INTO tracks (name) VALUES (?) ON CONFLICT (name) DO NOTHING"
      def loadTracks(remainingTracks: Set[String]): Task[Map[String, Int]] = {
        NonEmptySet.fromSet(SortedSet.from(remainingTracks)).fold(Task.pure(Map.empty[String, Int])) { nonEmpty =>
          val selectTracks = fr"SELECT name, id FROM tracks WHERE" ++ Fragments.in(fr"name", nonEmpty)
          Update[String](insert)
            .updateMany(nonEmpty)
            .flatMap { _ =>
              selectTracks.query[(String, Int)].toMap
            }
            .transact(rwt.write)
        }
      }

      for {
        cachedTracks <- UIO.delay(cache.getAllPresent(tracks.asJava).asScala.toMap)
        loadedTracks <- loadTracks(tracks.diff(cachedTracks.keySet))
        _            <- UIO.delay(cache.putAll(loadedTracks.asJava))
      } yield cachedTracks ++ loadedTracks
    }
  }

  def apply(config: TrackConfig, xas: Transactors): TrackStore = new TrackStoreImpl(config, xas)

}

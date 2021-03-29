package ch.epfl.bluebrain.nexus.migration.replay

import com.datastax.oss.driver.api.core.uuid.Uuids
import com.typesafe.config.Config

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration._

/**
  * Settings to replay events, matching the one available in akka persistence cassandra
  *
  * @see https://doc.akka.io/docs/akka-persistence-cassandra/current/configuration.html
  */
final case class ReplaySettings(
    keyspace: String,
    bucketSize: BucketSize,
    firstTimeBucket: TimeBucket,
    refreshInterval: FiniteDuration,
    eventualConsistency: FiniteDuration,
    maxBufferSize: Int
) {

  val firstOffset: UUID = Uuids.startOf(firstTimeBucket.key)
}

object ReplaySettings {

  private val firstBucketFormat = "yyyyMMdd'T'HH:mm"

  private val firstBucketFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern(firstBucketFormat).withZone(ZoneOffset.UTC)

  /**
    * Build the settings from the akka persistence cassandra configuration
    * @param config the config
    */
  def from(config: Config): ReplaySettings = {

    val replayConfig = config.getConfig("migration.replay")

    val keyspace = replayConfig.getString("keyspace")

    val bucketSize: BucketSize =
      BucketSize.fromString(replayConfig.getString("bucket-size"))

    val firstTimeBucket: TimeBucket = {
      val firstBucket         = replayConfig.getString("first-time-bucket")
      val firstBucketPadded   = (bucketSize, firstBucket) match {
        case (_, fb) if fb.length == 14               => fb
        case (BucketSize.Hour, fb) if fb.length == 11 => s"$fb:00"
        case (BucketSize.Day, fb) if fb.length == 8   => s"${fb}T00:00"
        case _                                        =>
          throw new IllegalArgumentException(s"Invalid first-time-bucket format. Use: $firstBucketFormat")
      }
      val date: LocalDateTime =
        LocalDateTime.parse(firstBucketPadded, firstBucketFormatter)
      TimeBucket(date.toInstant(ZoneOffset.UTC).toEpochMilli, bucketSize)
    }

    val refreshInterval = replayConfig.getDuration("refresh-interval", MILLISECONDS).millis

    val eventualConsistency = replayConfig.getDuration("eventual-consistency-delay", MILLISECONDS).millis

    val maxBufferSize = replayConfig.getInt("max-buffer-size")

    ReplaySettings(keyspace, bucketSize, firstTimeBucket, refreshInterval, eventualConsistency, maxBufferSize)
  }
}

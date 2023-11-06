package ch.epfl.bluebrain.nexus.storage.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.storage.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod
import ch.epfl.bluebrain.nexus.storage.config.AppConfig._

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

/**
  * Application configuration
  *
  * @param description
  *   service description
  * @param http
  *   http interface configuration
  * @param storage
  *   storages configuration
  * @param authorization
  *   authorization configuration
  * @param mediaTypeDetector
  *   media type configuration
  * @param digest
  *   the digest configuration
  */
final case class AppConfig(
    description: Description,
    http: HttpConfig,
    storage: StorageConfig,
    authorization: AuthorizationMethod,
    mediaTypeDetector: MediaTypeDetectorConfig,
    digest: DigestConfig
)

object AppConfig {

  /**
    * Service description
    *
    * @param name
    *   service name
    */
  final case class Description(name: String) {

    /**
      * @return
      *   the version of the service
      */
    val version: String = BuildInfo.version

    /**
      * @return
      *   the full name of the service (name + version)
      */
    val fullName: String = s"$name-${version.replaceAll("\\W", "-")}"

  }

  /**
    * HTTP configuration
    *
    * @param interface
    *   interface to bind to
    * @param port
    *   port to bind to
    * @param prefix
    *   prefix to add to HTTP routes
    * @param publicUri
    *   public URI of the service
    */
  final case class HttpConfig(interface: String, port: Int, prefix: String, publicUri: Uri)

  /**
    * Storages configuration
    *
    * @param rootVolume
    *   the base [[Path]] where the files are stored
    * @param protectedDirectory
    *   the relative [[Path]] of the protected directory once the storage bucket is selected
    * @param fixerEnabled
    *   call the permissions fixer when linking files, before they are moved
    * @param fixerCommand
    *   the command and arguments to call the 'nexus-fixer' binary, e.g. List("sudo", "nexus-fixer")
    */
  final case class StorageConfig(
      rootVolume: Path,
      extraPrefixes: List[Path],
      protectedDirectory: Path,
      fixerEnabled: Boolean,
      fixerCommand: Vector[String]
  )

  /**
    * The digest configuration.
    *
    * @param algorithm
    *   the digest algorithm
    * @param maxInMemory
    *   the maximum number of algorithms stored in memory
    * @param concurrentComputations
    *   the maximum number of concurrent computations of digest
    * @param maxInQueue
    *   the maximum number of computations in queue to be computed
    * @param retriggerAfter
    *   the amout of time after a digest which is still in the queue to be computed can be retrigger
    */
  final case class DigestConfig(
      algorithm: String,
      maxInMemory: Long,
      concurrentComputations: Int,
      maxInQueue: Int,
      retriggerAfter: FiniteDuration
  )

  implicit def toStorage(implicit config: AppConfig): StorageConfig = config.storage
  implicit def toHttp(implicit config: AppConfig): HttpConfig       = config.http
  implicit def toDigest(implicit config: AppConfig): DigestConfig   = config.digest

  val orderedKeys: OrderedKeys = OrderedKeys(
    List(
      "@context",
      "@id",
      "@type",
      "reason",
      "message",
      "details",
      "filename",
      "location",
      "bytes",
      ""
    )
  )
}

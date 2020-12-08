package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.S3Settings.S3Credentials

/**
  * S3 connection settings.
  *
  * @param credentials optional credentials
  * @param endpoint    optional endpoint, either a domain or a full URL
  * @param region      optional region
  */
final case class S3Settings(credentials: Option[S3Credentials], endpoint: Option[String], region: Option[String]) {

  private val regexScheme = "^(https?)(:\\/\\/)(.*)".r

  private[storage] def address(bucket: String): Uri =
    endpoint match {
      case Some(regexScheme(scheme, _, host)) => s"$scheme://$bucket.$host"
      case Some(host)                         => s"https://$bucket.$host"
      case None                               => region.fold(s"https://$bucket.s3.amazonaws.com")(r => s"https://$bucket.s3.$r.amazonaws.com")
    }
}

object S3Settings {

  /**
    * S3 credentials.
    *
    * @param accessKey the AWS access key ID
    * @param secretKey the AWS secret key
    */
  final case class S3Credentials(accessKey: String, secretKey: String)
}

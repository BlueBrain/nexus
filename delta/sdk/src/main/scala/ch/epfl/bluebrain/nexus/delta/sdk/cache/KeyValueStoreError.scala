package ch.epfl.bluebrain.nexus.delta.sdk.cache

import scala.concurrent.duration.FiniteDuration

sealed abstract class KeyValueStoreError(msg: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): Throwable = this
  override def getMessage: String            = msg
}

object KeyValueStoreError {

  /**
    * Signals that a timeout occurred while waiting for the desired read or write consistency across nodes.
    *
    * @param timeout the timeout duration
    */
  final case class ReadWriteConsistencyTimeout(timeout: FiniteDuration)
      extends KeyValueStoreError(
        s"Timed out after '${timeout.toMillis} ms' while waiting for a consistent read or write."
      )

  /**
    * Signals that an error occurred when trying to perform a distributed data operation.
    */
  final case class DistributedDataError(reason: String)
      extends KeyValueStoreError(s"An error occurred when performing a distributed data operation, reason '$reason'.")

}

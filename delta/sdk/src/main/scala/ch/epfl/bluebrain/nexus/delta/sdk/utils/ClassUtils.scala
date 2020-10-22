package ch.epfl.bluebrain.nexus.delta.sdk.utils

@SuppressWarnings(Array("UnsafeTraversableMethods"))
trait ClassUtils {

  /**
    * @return the simple name of the class from the passed ''value''.
    */
  // TODO: Move this to the kernel module when available (it will be used in sourcing and RDF too
  def simpleName[A](value: A): String =
    value.getClass.getSimpleName.split('$').head
}

object ClassUtils extends ClassUtils

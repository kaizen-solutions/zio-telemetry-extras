package io.kaizensolutions.opentelemetry

import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString
import zio.telemetry.opentelemetry.context.IncomingContextCarrier

package object http4s {
  private[opentelemetry] def incomingHeadersCarrier(
      headers: Headers
  ): IncomingContextCarrier[Headers] =
    new IncomingContextCarrier[Headers] {
      override val kernel: Headers = headers

      override def getAllKeys(carrier: Headers): Iterable[String] =
        carrier.headers.map(_.name.toString)

      override def getByKey(carrier: Headers, key: String): Option[String] =
        carrier
          .get(CIString(key))
          .map(_.head.value)
    }

  private[opentelemetry] def outgoingHeaders(
      in: scala.collection.mutable.Map[String, String]
  ): Headers = {
    val headers = in.map { case (k, v) => Header.Raw(CIString(k), v) }.toList
    Headers(headers)
  }
}

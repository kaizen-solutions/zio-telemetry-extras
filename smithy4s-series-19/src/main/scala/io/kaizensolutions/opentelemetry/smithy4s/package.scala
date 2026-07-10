package io.kaizensolutions.opentelemetry

import io.opentelemetry.api.common.Attributes

import _root_.smithy4s.{Endpoint, Service}

package object smithy4s {
  def attributes[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
      endpoint: Endpoint[service.Operation, ?, ?, ?, ?, ?]
  ) = {
    val http    = endpoint.hints.get[smithy.api.Http]
    val version = Option(service.version.strip()).filter(_.nonEmpty)

    val resourceName =
      http.map(h => s"${h.method.value} ${h.uri.value}")

    val builder         = Attributes.builder()
    val builderWithOpts =
      Map(
        "smithy.service.version" -> version,
        "resource.name"          -> resourceName
      )
        .collect { case (k, Some(v)) => k -> v }
        .foldLeft(builder) { case (builder, (k, v)) => builder.put(k, v) }

    builderWithOpts
      .put("smithy.service.name", service.id.name)
      .put("smithy.operation", endpoint.id.name)
      .build()
  }
}

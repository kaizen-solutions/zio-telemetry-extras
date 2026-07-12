package io.kaizensolutions.metrics

import zio.metrics.MetricLabel

import _root_.smithy4s.{Endpoint, Service}

package object smithy4s {
  def metricLabels[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
      endpoint: Endpoint[service.Operation, ?, ?, ?, ?, ?]
  ): Set[MetricLabel] = {
    val http    = endpoint.hints.get[smithy.api.Http]
    val version = Option(service.version.strip()).filter(_.nonEmpty)

    val resourceName =
      http.map(h => s"${h.method.value} ${h.uri.value}")

    val optLabels =
      Map(
        "smithy.service.version" -> version,
        "resource.name"          -> resourceName
      ).collect { case (k, Some(v)) => MetricLabel(k, v) }

    val mandatoryLabels = Set(
      MetricLabel("smithy.service.name", service.id.name),
      MetricLabel("smithy.operation", endpoint.id.name)
    )

    mandatoryLabels ++ optLabels
  }
}

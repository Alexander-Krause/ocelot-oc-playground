// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: dump/v1/dump.proto

package io.opencensus.proto.dump;

public interface DumpSpansOrBuilder extends
    // @@protoc_insertion_point(interface_extends:opencensus.proto.dump.v1.DumpSpans)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>repeated .opencensus.proto.trace.v1.Span spans = 1;</code>
   */
  java.util.List<io.opencensus.proto.trace.v1.Span> 
      getSpansList();
  /**
   * <code>repeated .opencensus.proto.trace.v1.Span spans = 1;</code>
   */
  io.opencensus.proto.trace.v1.Span getSpans(int index);
  /**
   * <code>repeated .opencensus.proto.trace.v1.Span spans = 1;</code>
   */
  int getSpansCount();
  /**
   * <code>repeated .opencensus.proto.trace.v1.Span spans = 1;</code>
   */
  java.util.List<? extends io.opencensus.proto.trace.v1.SpanOrBuilder> 
      getSpansOrBuilderList();
  /**
   * <code>repeated .opencensus.proto.trace.v1.Span spans = 1;</code>
   */
  io.opencensus.proto.trace.v1.SpanOrBuilder getSpansOrBuilder(int index);
}

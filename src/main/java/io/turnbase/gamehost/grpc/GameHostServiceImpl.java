package io.turnbase.gamehost.grpc;

import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import org.springframework.stereotype.Component;

/**
 * Proves the {@code io.turnbase:turnbase-grpc} server base class resolves, compiles and links
 * (IMP-M0-04, IMP-GATE-03). Every RPC inherits the generated base class's {@code UNIMPLEMENTED}
 * response until IMP-M3-04 wires the real dispatch behaviour — CreateSession, ProcessInput, Resync,
 * TerminateSession.
 */
@Component
class GameHostServiceImpl extends GameHostServiceGrpc.GameHostServiceImplBase {}

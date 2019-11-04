package org.apache.platypus.server;

import io.grpc.stub.StreamObserver;
import org.apache.lucene.replicator.nrt.CopyJob;
import org.apache.lucene.replicator.nrt.FileMetaData;
import org.apache.platypus.server.grpc.CopyFiles;
import org.apache.platypus.server.grpc.TransferStatus;
import org.apache.platypus.server.grpc.TransferStatusCode;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class CopyFilesHandler implements Handler<CopyFiles, TransferStatus> {
    @Override
    public void handle(IndexState indexState, CopyFiles copyFilesRequest, StreamObserver<TransferStatus> responseObserver) throws Exception {
        String indexName = copyFilesRequest.getIndexName();
        ShardState shardState = indexState.getShard(0);

        if (shardState.isReplica() == false) {
            throw new IllegalArgumentException("index \"" + indexName + "\" is not a replica or was not started yet");
        }

        if (!isValidMagicHeader(copyFilesRequest.getMagicNumber())) {
            throw new RuntimeException("RecvCopyStateHandler invoked with Invalid Magic Number");
        }

        long primaryGen = copyFilesRequest.getPrimaryGen();
        // these are the files that the remote (primary) wants us to copy
        Map<String, FileMetaData> files = NRTReplicaNode.readFilesMetaData(copyFilesRequest.getFilesMetadata());

        AtomicBoolean finished = new AtomicBoolean();
        try{
            CopyJob job = shardState.nrtReplicaNode.launchPreCopyFiles(finished, primaryGen, files);
        } catch (IOException e) {
            responseObserver.onNext(TransferStatus.newBuilder().setMessage(String.format("replica failed to launchPreCopyFiles" + files.keySet())).setCode(TransferStatusCode.Failed).build());
            //called must set; //responseObserver.onError(e);
            throw new RuntimeException(e);
        }

        // we hold open this request, only finishing/closing once our copy has finished, so primary knows when we finished
        while (true) {
            // nocommit don't poll!  use a condition...
            if (finished.get()) {
                responseObserver.onNext(TransferStatus.newBuilder().setMessage("replica is done copying files.." + files.keySet()).setCode(TransferStatusCode.Done).build());
                responseObserver.onCompleted();
                break;
            }
            try {
                Thread.sleep(10);
                responseObserver.onNext(TransferStatus.newBuilder().setMessage("replica is copying files..." + files.keySet()).setCode(TransferStatusCode.Ongoing).build());
            } catch (InterruptedException e) {
                responseObserver.onNext(TransferStatus.newBuilder().setMessage(String.format("replica failed to copy files..." + files.keySet())).setCode(TransferStatusCode.Failed).build());
                //called must set; //responseObserver.onError(e);
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public TransferStatus handle(IndexState indexState, CopyFiles protoRequest) throws HandlerException {
        throw new UnsupportedOperationException("This method is in not implemented for this class");
    }
}

package com.iota.iri.controllers;

import com.iota.iri.TransactionValidator;
import com.iota.iri.conf.Configuration;
import com.iota.iri.model.Hash;
import com.iota.iri.model.Transaction;
import com.iota.iri.network.Node;
import com.iota.iri.storage.Tangle;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Created by paul on 3/27/17.
 */
public class TransactionRequester {

    private final Logger log = LoggerFactory.getLogger(TransactionRequester.class);
    private final Set<Hash> milestoneTransactionsToRequest = new LinkedHashSet<>();
    private final Set<Hash> transactionsToRequest = new LinkedHashSet<>();
    private static volatile long lastTime = System.currentTimeMillis();
    public  static final int REQUEST_HASH_SIZE = 49;
    private static final byte[] NULL_REQUEST_HASH_BYTES = new byte[REQUEST_HASH_SIZE];

    private static double P_REMOVE_REQUEST;
    private static boolean initialized = false;
    private static final TransactionRequester instance = new TransactionRequester();
    private final SecureRandom random = new SecureRandom();

    private final Object syncObj = new Object();

    public static void init(double p_REMOVE_REQUEST) {
        if(!initialized) {
            initialized = true;
            P_REMOVE_REQUEST = p_REMOVE_REQUEST;
        }
    }

    public void rescanTransactionsToRequest() throws Exception {
        Transaction first = (Transaction) Tangle.instance().getFirst(Transaction.class);
        if(first != null) {
            TransactionViewModel transaction = TransactionValidator.validate(first.bytes);
            transaction.quickSetSolid();
            while((transaction = TransactionValidator.validate(((Transaction) Tangle.instance().next(Transaction.class, transaction.getHash())).bytes)) != null) {
                transaction.quickSetSolid();
            }
        }
    }
    public Hash[] getRequestedTransactions() {
        synchronized (syncObj) {
            return ArrayUtils.addAll(transactionsToRequest.stream().toArray(Hash[]::new),
                    milestoneTransactionsToRequest.stream().toArray(Hash[]::new));
        }
    }

    public int numberOfTransactionsToRequest() {
        return transactionsToRequest.size() + milestoneTransactionsToRequest.size();
    }

    public boolean clearTransactionRequest(Hash hash) {
        synchronized (syncObj) {
            boolean milestone = milestoneTransactionsToRequest.remove(hash);
            boolean normal = transactionsToRequest.remove(hash);
            return normal || milestone;
        }
    }

    public void requestTransactions(Set<Hash> hashes, boolean milestone) throws Exception {
        synchronized (syncObj) {
            for(Hash hash: hashes) {
                requestTransaction(hash, milestone);
            }
        }
    }

    public void requestTransaction(Hash hash, boolean milestone) throws Exception {
        if (!hash.equals(Hash.NULL_HASH) && !TransactionViewModel.exists(hash)) {
            synchronized (syncObj) {
                if(milestone) {
                    transactionsToRequest.remove(hash);
                    milestoneTransactionsToRequest.add(hash);
                } else {
                    if(!milestoneTransactionsToRequest.contains(hash)) {
                        transactionsToRequest.add(hash);
                    }
                }
            }
        }
    }


    public Hash transactionToRequest(boolean milestone) throws Exception {
        final long beginningTime = System.currentTimeMillis();
        Hash hash = null;
        Set<Hash> requestSet;
        if(milestone) {
             requestSet = milestoneTransactionsToRequest;
             if(requestSet.size() == 0) {
                 requestSet = transactionsToRequest;
             }
        } else {
            requestSet = transactionsToRequest;
            if(requestSet.size() == 0) {
                requestSet = milestoneTransactionsToRequest;
            }
        }
        synchronized (syncObj) {
            while (requestSet.size() != 0) {
                Iterator<Hash> iterator = requestSet.iterator();
                hash = iterator.next();
                iterator.remove();
                if (TransactionViewModel.exists(hash)) {
                    log.info("Removed existing tx from request list: " + hash);
                } else {
                    requestSet.add(hash);
                    break;
                }
            }
        }

        if(random.nextDouble() < P_REMOVE_REQUEST && !requestSet.equals(milestoneTransactionsToRequest)) {
            synchronized (syncObj) {
                transactionsToRequest.remove(hash);
            }
        }

        long now = System.currentTimeMillis();
        if ((now - lastTime) > 10000L) {
            lastTime = now;
            //log.info("Transactions to request = {}", numberOfTransactionsToRequest() + " / " + TransactionViewModel.getNumberOfStoredTransactions() + " (" + (now - beginningTime) + " ms ). " );
        }
        return hash;
    }

    public static TransactionRequester instance() {
        return instance;
    }

}

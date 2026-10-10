package com.pab.ficc.ibp.modelgate.server.adapter;

import java.net.URI;
import org.springframework.http.HttpMethod;

public record ProbePlan(HttpMethod method, URI clientUri, byte[] body) { }

/*
 * Copyright 2010 the original author or authors
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package org.springframework.integration.twitter.outbound;

import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.util.Assert;

import twitter4j.Twitter;


/**
 * Base adapter class for all outbound Twitter adapters
 *
 * @author Josh Long
 * @since 2.0
 */
public abstract class AbstractOutboundTwitterEndpointSupport extends AbstractMessageHandler {
	//protected volatile OAuthConfiguration configuration;
	protected final Twitter twitter;
	protected final OutboundStatusUpdateMessageMapper supportStatusUpdate = new OutboundStatusUpdateMessageMapper();

	public AbstractOutboundTwitterEndpointSupport(Twitter twitter){
		Assert.notNull(twitter, "'twitter' must not be null");
		this.twitter = twitter;
	}
//	public void setConfiguration(OAuthConfiguration configuration) {
//		this.configuration = configuration;
//	}

//	@Override
//	protected void onInit() throws Exception {
//		Assert.notNull(this.configuration, "'configuration' can't be null");
//		this.twitter = this.configuration.getTwitter();
//		Assert.notNull(this.twitter, "'twitter' can't be null");
//	}

}

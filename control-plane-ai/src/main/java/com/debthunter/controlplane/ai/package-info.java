/**
 * Zone E — AI assist: the prompt guard, explanation service, remediation proposals, and provenance
 * labelling.
 *
 * <p>Every model dispatch passes through the prompt guard, which fails closed: source content is
 * only sent when a repository has explicitly opted in. All generated text is labelled as such.
 */
package com.debthunter.controlplane.ai;

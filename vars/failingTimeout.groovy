/* Copyright (c) 2019 - 2019 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 * 
 * failingTimeout()
 * Description: Wraps a timeout call and throws an error rather than an abort if the timeout is exceeded.
 * Modified from https://support.cloudbees.com/hc/en-us/articles/226554067/comments/360000870712
 * args:
 *  conf: Map is passed directly to the timeout step, see https://jenkins.io/doc/pipeline/steps/workflow-basic-steps/#timeout-enforce-time-limit
 *  body: Closure that is wrapped by timeout
 */

def call(Map conf, Closure body) {
    try {
        timeout(conf) {
            body()
        }
    } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException err) {
        errorCause = err.causes.get(0)
        // Specially handle the timeout case
        if(errorCause instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) { // SYSTEM means timeout.
            error "Build failed due to timeout."
        } else {
            throw err
        }
    }
}
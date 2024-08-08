/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.problems.internal.emitters;

import org.gradle.api.Incubating;
import org.gradle.api.problems.internal.DefaultProblemProgressDetails;
import org.gradle.api.problems.internal.DefaultProblems;
import org.gradle.api.problems.internal.Problem;
import org.gradle.api.problems.internal.ProblemEmitter;
import org.gradle.internal.operations.BuildOperationProgressEventEmitter;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.util.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits problems as build operation progress events.
 *
 * @since 8.6
 */
@Incubating
public class BuildOperationBasedProblemEmitter implements ProblemEmitter {

    private final BuildOperationProgressEventEmitter eventEmitter;

    public BuildOperationBasedProblemEmitter(BuildOperationProgressEventEmitter eventEmitter) {
        this.eventEmitter = eventEmitter;
    }

    @Override
    public void emit(Problem problem, OperationIdentifier id) {
        // if task is being executed; associate problem with task
        Path path = DefaultProblems.taskPath.get();
        if (path != null) {
            List<Problem> problems = DefaultProblems.problems.get();
            if (problems == null) {
                problems = new ArrayList<>();
            }
            problems.add(problem);
            DefaultProblems.problems.set(problems);
        }
        eventEmitter.emitNow(id, new DefaultProblemProgressDetails(problem));
    }
}

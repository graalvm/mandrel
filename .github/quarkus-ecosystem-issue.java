/*
 * Copyright 2020 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.kohsuke:github-api:1.318
//DEPS info.picocli:picocli:4.2.0

import org.kohsuke.github.*;
import org.kohsuke.github.GHWorkflowRun.Conclusion;
import org.kohsuke.github.function.InputStreamFunction;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;

@Command(name = "report", mixinStandardHelpOptions = true,
		description = "Takes care of updating an issue depending on the status of the build")
class Report implements Runnable {

	@Option(names = "token", description = "Github token to use when calling the Github API")
	private String token;

	@Deprecated
	@Option(names = "status", description = "The status of the CI run")
	private String status;

	@Option(names = "issueRepo", description = "The repository where the issue resides (i.e. quarkusio/quarkus)")
	private String issueRepo;

	@Option(names = "issueNumber", description = "The issue to update")
	private Integer issueNumber;

	@Option(names = "thisRepo", description = "The repository for which we are reporting the CI status")
	private String thisRepo;

	@Option(names = "runId", description = "The ID of the Github Action run for  which we are reporting the CI status")
	private String runId;

	
	@Option(names = "--dry-run", description = "Whether to actually update the issue or not")
	private boolean dryRun;

	@Override
	public void run() {
		try {
			final GitHub github = new GitHubBuilder().withOAuthToken(token).build();
			final GHRepository issueRepository = github.getRepository(issueRepo);
			final GHRepository workflowRepository = github.getRepository(thisRepo);
			GHWorkflowRun workflowRun = workflowRepository.getWorkflowRun(Long.parseLong(runId));
			Conclusion status = workflowRun.getConclusion();

			System.out.println(String.format("The CI build had status %s.", status));

				
			if (status.equals(Conclusion.CANCELLED) || status.equals(Conclusion.SKIPPED)) {
				System.out.println("Job status is `cancelled` or `skipped` - exiting");
				System.exit(0);
			}


			final GHIssue issue = issueRepository.getIssue(issueNumber);
			if (issue == null) {
				System.out.println(String.format("Unable to find the issue %s in project %s", issueNumber, issueRepo));
				System.exit(-1);
			} else {
				System.out.println(String.format("Report issue found: %s - %s", issue.getTitle(), issue.getHtmlUrl().toString()));
				System.out.println(String.format("The issue is currently %s", issue.getState().toString()));
			}

			if (status.equals(Conclusion.SUCCESS)) {
				if (issue != null  && isOpen(issue)) {
					String comment = String.format("Build fixed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s", thisRepo, runId);
					if (!dryRun) {
						// close issue with a comment
						issue.comment(comment);
						issue.close();
					}
					System.out.println(String.format("Comment added on issue %s\n%s\n, the issue has also been closed", issue.getHtmlUrl().toString(), comment));
				} else {
					System.out.println("Nothing to do - the build passed and the issue is already closed");
				}
			} else  {

				/*
				 * If the issue contains a line like:
				 * 
				 * Filter: Q main G 22 latest
				 * 
				 * then we will only report on the jobs that contain "Q main G 22" in their name, e.g. "Q main G 22 latest / Q IT Misc2".
				 * This is useful when the github action contains multiple reusable jobs and we want to use a different issue for each of them.
				 */
				final String filter;
				String body = issue.getBody();
				if (body != null) {
					String regex = "^Job Filter: (.*)$";
					Pattern pattern = Pattern.compile(regex);
					Matcher matcher = pattern.matcher(body);
					if (matcher.find()) {
						filter = matcher.group(1);
					} else {
						filter = "";
					}
				} else {
					filter = "";
				}

				Predicate<? super GHWorkflowJob> predicate;
				if (filter != "") {
					System.out.println(String.format("Getting logs from failed jobs with names containing: %s", filter));
					predicate = job -> job.getConclusion().equals(Conclusion.FAILURE) && job.getName().contains(filter);
				} else {
					System.out.println("Getting logs from all failed jobs");
					predicate = job -> job.getConclusion().equals(Conclusion.FAILURE);
				}

				StringBuilder sb = new StringBuilder("Failed jobs:\n");
				workflowRun.listJobs().toList().stream().filter(predicate).forEach(job -> {
					sb.append(String.format("* [%s](%s)\n", job.getName(), job.getHtmlUrl()));
					job.getSteps().stream().filter(step -> step.getConclusion().equals(Conclusion.FAILURE)).forEach(step -> {
						sb.append(String.format("  * Step: %s\n", step.getName()));
					});
					String fullContent = "";
					try {
						fullContent = job.downloadLogs(getLogArchiveInputStreamFunction());
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					if (!fullContent.isEmpty()) {
						sb.append("    Filtered logs:\n");
						sb.append(String.format("```\n%s```\n", fullContent));
					}
				});

				if (isOpen(issue)) {
					String comment = String.format("The build is still failing!\n\n%s\nLink to latest CI run: https://github.com/%s/actions/runs/%s", sb.toString(), thisRepo, runId);
					if (!dryRun) {
						issue.comment(comment);
					}
					System.out.println(String.format("Comment added on issue %s\n%s", issue.getHtmlUrl().toString(), comment));
				} else {
					String comment = String.format("Unfortunately, the build failed!\n\n%s\nLink to latest CI run: https://github.com/%s/actions/runs/%s", sb.toString(), thisRepo, runId);
					if (!dryRun) {
						issue.reopen();
						issue.comment(comment);
					}
					System.out.println(String.format("Comment added on issue %s\n%s, the issue has been re-opened", issue.getHtmlUrl().toString(), comment));
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

    private static InputStreamFunction<String> getLogArchiveInputStreamFunction() {
        return (is) -> {
			StringBuilder stringBuilder = new StringBuilder();
			try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is))) {
				String line;
				while ((line = bufferedReader.readLine()) != null) {
					if (line.contains("FAILURE [") || line.contains("Error:")) {
						stringBuilder.append(line);
						stringBuilder.append(System.lineSeparator());
					}
				}
			}
			return stringBuilder.toString();
        };
    }

	private static boolean isOpen(GHIssue issue) {
		return (issue.getState() == GHIssueState.OPEN);
	}
	
	public static void main(String... args) {
		int exitCode = new CommandLine(new Report()).execute(args);
		System.exit(exitCode);
	}
}

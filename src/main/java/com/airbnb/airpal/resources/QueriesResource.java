package com.airbnb.airpal.resources;

import com.airbnb.airpal.api.Job;
import com.airbnb.airpal.api.JobState;
import com.airbnb.airpal.core.AuthorizationUtil;
import com.airbnb.airpal.core.store.JobHistoryStore;
import com.airbnb.airpal.presto.PartitionedTable;
import com.airbnb.airpal.presto.Table;
import com.facebook.presto.client.Column;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.inject.Inject;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.Collections;
import java.util.List;

import static com.airbnb.airpal.resources.QueryResource.JOB_ORDERING;

@Path("/api/queries")
@Produces({MediaType.APPLICATION_JSON})
public class QueriesResource
{
    private final JobHistoryStore jobHistoryStore;

    @Inject
    public QueriesResource(JobHistoryStore jobHistoryStore)
    {
        this.jobHistoryStore = jobHistoryStore;
    }

    @GET
    public Response getQueries(
            @QueryParam("results") int numResults,
            @QueryParam("table") List<PartitionedTable> tables)
    {
        Subject subject = SecurityUtils.getSubject();
        Iterable<Job> recentlyRun;
        int results = Optional.of(numResults).or(200);

        if (tables.size() < 1) {
            recentlyRun = jobHistoryStore.getRecentlyRun(results);
        } else {
            recentlyRun = jobHistoryStore.getRecentlyRun(
                    results,
                    Iterables.transform(tables, new PartitionedTable.PartitionedTableToTable()));
        }

        ImmutableList.Builder<Job> filtered = ImmutableList.builder();
        for (Job job : recentlyRun) {
            if (job.getTablesUsed().isEmpty() && (job.getState() == JobState.FAILED)) {
                filtered.add(job);
                continue;
            }
            for (Table table : job.getTablesUsed()) {
                if (AuthorizationUtil.isAuthorizedRead(subject, table)) {
                    filtered.add(new Job(
                            job.getUser(),
                            job.getQuery(),
                            job.getUuid(),
                            job.getOutput(),
                            job.getQueryStats(),
                            job.getState(),
                            Collections.<Column>emptyList(),
                            Collections.<Table>emptySet(),
                            job.getQueryStartedDateTime(),
                            job.getError(),
                            job.getQueryFinishedDateTime()));
                }
            }
        }

        List<Job> sortedResult = Ordering
                .natural()
                .nullsLast()
                .onResultOf(JOB_ORDERING)
                .reverse()
                .immutableSortedCopy(filtered.build());
        return Response.ok(sortedResult).build();
    }
}
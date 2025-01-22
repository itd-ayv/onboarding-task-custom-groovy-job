package org.example

import com.niku.xmlserver.blob.NkCalendar
import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import de.itdesign.clarity.logging.CommonLogger
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.time.TimeCategory
import groovy.transform.Field
import java.sql.Blob
import java.sql.Connection
import org.example.utils.dbUtil
import java.time.LocalDate

Connection connection = dbUtil.connect()
sql = new Sql(connection)

@Field CommonLogger cmnLog = new CommonLogger(this)
cmnLog.setFailJobOnError(true)

PROJECT_NAME = null
RESOURCE_NAME = null
FROM_DATE = null
TO_DATE = null
PERIOD = null

List<GroovyRowResult> getSrcOtherWorkData(String ciType) {
    println "Executing query to retrieve source data for CI Type: $ciType"
    def query = """
               SELECT 
                   src.id, src.code, src.name, src.schedule_start, src.schedule_finish 
               FROM 
                   inv_investments src 
                   INNER JOIN inv_investments tgt 
                       ON UPPER(CONCAT(SUBSTR(tgt.code, 0, 3), SUBSTR(tgt.code, 4, 6))) = src.code 
                       AND tgt.odf_object_code = ?
            """
    def result = sql.rows(query, [ciType])
    println "Retrieved Source Data: ${result}"
    return result
}

// Fetch team data based on investment ID and date range
List<GroovyRowResult> getTeamData(Integer investmentId, Date fromDate, Integer periods) {
    println "Fetching team data for Investment ID: $investmentId, From Date: $fromDate, Periods: $periods"
    LocalDate localStartDate = fromDate.toLocalDate()
    LocalDate localEndDate = localStartDate.plusMonths(periods)
    java.sql.Date sqlStartDate = java.sql.Date.valueOf(localStartDate)
    java.sql.Date sqlEndDate = java.sql.Date.valueOf(localEndDate)
    println(sqlStartDate)
    println(sqlEndDate)

    def query = """
        select 
            team.PRUID,
            team.prid,
            team.PRRESOURCEID,
            team.PRALLOCCURVE,
            team.HARD_CURVE,
            team.PRAVAILSTART,
            team.PRAVAILFINISH
        from 
            prteam team
        inner join inv_investments ii on ii.id = team.prprojectid
        where ii.id = ? 
        
    """

    def result = sql.rows(query, [investmentId])
    println "Team Data Retrieved: ${result}"

    return result
}

// Convert Blob data to NkCurve object
NkCurve getCurveFromBlob(GroovyRowResult eachResource, String curveName) {
    println "Converting Blob to NkCurve for curve: $curveName"
    Blob curveBlob = eachResource?.get(curveName)
    if (!curveBlob) {
        println "Error: Curve Blob for $curveName is null."
        return null
    }
    byte[] curveBytes = curveBlob ? curveBlob.getBytes(1, (int) curveBlob.length()) : null
    NkCurve curve = curveBytes ? new NkCurve(curveBytes) : null
    if (!curve) {
        println "Error: Failed to create NkCurve from Blob for $curveName."
    }
    return curve
}

// Split the curve into time segments based on the given period
NkCurve splitCurveByPeriod(NkCurve curve, Date start, Integer periods) {
    println "Splitting curve into segments for period starting on $start and period count: $periods"
    if (!curve) {
        println "Error: curve is null, cannot split."
        return null
    }

    NkCurve splitCurve = new NkCurve(1)
    use(TimeCategory) {
        for (int i = 0; i < periods; i++) {
            def segmentStartDate = start + i.month
            def segmentEndDate = start + i.month + 1.month
            println "Processing period: ${i + 1}, Start: $segmentStartDate, End: $segmentEndDate"
            curve.segments.each { NkSegment segment ->
                if (segment.startDate >= segmentStartDate && segment.finishDate <= segmentEndDate) {

                    println "Adding Segment: $segment"
                    splitCurve.segments.setSegment(segment)
                }
            }
            def value = curve.getSum(segmentStartDate, segmentEndDate)
            print("Added Value")
            println(value)
        }
    }

    println "Final Split Curve: $splitCurve"
    return splitCurve
}

// Write the data to CSV file
def writeToCSV(data, outputFile) {
    println "Writing data to CSV file: $outputFile"
    File file = new File(outputFile)
    file.withWriter { writer ->
        writer.writeLine("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)")
        data.each { row ->
            println "Writing Row: $row"
            writer.writeLine(row.join(","))
        }
    }
    println "CSV file has been written successfully."
}


def generateCsvForAllocations() {
    println "Starting CSV generation process"
    //List<GroovyRowResult> investments = getSrcOtherWorkData("project")
    // Create a list of maps, not GroovyRowResult
    List<Map<String, Object>> investments = [
            [ID: 5003000, CODE: 'PR2001', NAME: 'Website Redesign', SCHEDULE_START: '2024-12-12 08:00:00.000', SCHEDULE_FINISH: '2025-01-06 17:00:00.000']
    ]

    List<GroovyRowResult> groovyRowResults = investments.collect { new GroovyRowResult(it) }
    List<Map<String, Object>> csvData = []
    println "Processing Investments: ${investments.size()} found."

    groovyRowResults.each { investment ->
        println "Processing Investment: ${investment.name}"

        Date fromDate = FROM_DATE ?: Date.parse("yyyy-MM-dd HH:mm:ss.S", investment.SCHEDULE_START as String)
        Date toDate = TO_DATE ?: Date.parse("yyyy-MM-dd HH:mm:ss.S", investment.SCHEDULE_FINISH as String)

        Integer periods = getNumberOfMonthsBetween(fromDate, toDate)
        println "From Date: $fromDate, To Date: $toDate, Periods: $periods"
        println(investment.id + "id")
        List<GroovyRowResult> teamData = getTeamData(investment.id as Integer, fromDate, periods)


        teamData.each { sourceTeamData ->
            println "Processing Source Team Data: $sourceTeamData"
            NkCurve softCurve = getCurveFromBlob(sourceTeamData, "PRALLOCCURVE")
            println("Soft ")
            println(softCurve)
            NkCurve hardCurve = getCurveFromBlob(sourceTeamData, "HARD_CURVE")
            print("hard ")
            println(hardCurve)
            NkCalendar calender = new NkCalendar()

            def regex = /\((\d+(\.\d+)?)\)/

            def matches = (softCurve =~ regex)  // Find matches for the regex

            matches.each { match ->
                def hoursAsNumber = match[1].toDouble()
                println "Extracted hours: ${hoursAsNumber / 3600}"
            }
            // Handle case when curve is null
            if (!softCurve) {
                println "Skipping team data due to missing curves for investment ${investment.name} (ID: ${investment.id})"
                return // Skip processing this team data if curves are null
            }

            // Split the curves into monthly segments
            NkCurve splitSoftCurve = splitCurveByPeriod(softCurve, fromDate, periods)
            // NkCurve splitHardCurve = splitCurveByPeriod(hardCurve, fromDate, periods)

            // Ensure that the split curves are not null

            if (splitSoftCurve) {
                splitSoftCurve.segments.eachWithIndex { NkSegment segment, index ->
                    def row = [
                            investment.name,
                            sourceTeamData.PRRESOURCEID,
                            segment.startDate.format("dd.MMM.yyyy"),
                            segment.finishDate.format("dd.MMM.yyyy"),
                            //splitHardCurve.segments[index]?.rate ?: 0, // Hard Allocation
                            segment.rate // Soft Allocation
                    ]
                    println "Adding Row to CSV Data: $row"
                    csvData << row
                }
            } else {
                println "Warning: Could not split curves for investment ${investment.name} (ID: ${investment.id})"
            }
        }
    }

    writeToCSV(csvData, "allocation_output.csv")
    println "CSV Generation Complete."
}

// Calculate number of months between two dates
Integer getNumberOfMonthsBetween(Date startDate, Date endDate) {
    println "Calculating months between $startDate and $endDate"
    Calendar startCal = Calendar.getInstance()
    Calendar endCal = Calendar.getInstance()

    startCal.setTime(startDate)
    endCal.setTime(endDate)

    int yearsDiff = endCal.get(Calendar.YEAR) - startCal.get(Calendar.YEAR)
    int monthsDiff = endCal.get(Calendar.MONTH) - startCal.get(Calendar.MONTH)

    if (monthsDiff < 0) {
        yearsDiff--
        monthsDiff += 12
    }

    int totalMonthsDiff = (yearsDiff * 12) + monthsDiff
    println "Total months difference: $totalMonthsDiff"
    return totalMonthsDiff
}


void assertParameters() {
    cmnLog.info "Parameters passed to the job: [Target CI Type: ${binding.variables.get('z_project_name')}, Target PF Code: ${binding.variables.get('z_resource_name')}, From: ${binding.variables.get('z_from_date')}, To: ${binding.variables.get('z_to_date')}]"

    if (!binding.variables.containsKey("z_from_date") || z_from_date == null || z_from_date == "") {
        FROM_DATE = convertStringParameterToDate("date_startofcurrentyear")
    } else {
        FROM_DATE = getMonthStart(convertStringParameterToDate(z_from_date))
    }

    if (binding.variables.containsKey("z_to_date") && z_to_date != null && z_to_date != "") {
        TO_DATE = getMonthEnd(convertStringParameterToDate(z_to_date))
    }

    if (TO_DATE != null && FROM_DATE.after(TO_DATE)) {
        throw new Exception("The Date from when allocations to be read '${FROM_DATE}' lies after the Date until when allocations to be read '${TO_DATE}'")
    }
    PROJECT_NAME = binding.variables.get('z_project_name')
    RESOURCE_NAME = binding.variables.get('z_resource_name')
    PERIOD = binding.variables.get('z_period')

}


def runScript() {
    cmnLog.info "Started migrating allocations at:-${new Date()}"
    generateCsvForAllocations()
    //assertParameters()
//    def oldAutoCommit = sql.connection.autoCommit
//    sql.connection.autoCommit = true
//    assertParameters()
//    migrateOtherWorkAllocations()
//    sql.connection.autoCommit = oldAutoCommit
    cmnLog.info "Finished migrating allocations at:${new Date()}"
}

runScript()

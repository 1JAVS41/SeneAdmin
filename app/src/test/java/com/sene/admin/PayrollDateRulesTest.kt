package com.sene.admin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class PayrollDateRulesTest {
    @Test fun payrollPeriodUsesMostRecentConfiguredPayDay() {
        assertEquals("2026-11-13",PayrollDateRules.periodEndForDate("2026-11-18",Calendar.FRIDAY))
        assertEquals("2026-11-20",PayrollDateRules.periodEndForDate("2026-11-20",Calendar.FRIDAY))
        assertEquals("2026-11-15",PayrollDateRules.periodEndForDate("2026-11-18",Calendar.SUNDAY))
    }

    @Test fun attendanceWeekStartsOnMondayLikeSeneVentas() {
        assertEquals("2026-11-16",PayrollDateRules.mondayStartDate("2026-11-18"))
        assertEquals("2026-11-16",PayrollDateRules.mondayStartDate("2026-11-22"))
    }

    @Test fun attendanceStatesRemainDistinct() {
        assertEquals("EARLY_EXIT",PayrollDateRules.attendanceStatus("EARLY_EXIT","2026-11-18","2026-11-18",true,"2026-11-01"))
        assertEquals("ADMIN_CLOSED",PayrollDateRules.attendanceStatus("ADMIN_CLOSED","2026-11-18","2026-11-18",true,"2026-11-01"))
        assertEquals("WORKING",PayrollDateRules.attendanceStatus("WORKING","2026-11-18","2026-11-18",true,"2026-11-01"))
        assertEquals("NO_RECORD",PayrollDateRules.attendanceStatus("","2026-11-10","2026-11-18",true,"2026-11-12"))
        assertEquals("NOT_WORKED",PayrollDateRules.attendanceStatus("","2026-11-17","2026-11-18",true,"2026-11-12"))
        assertEquals("REST_DAY",PayrollDateRules.attendanceStatus("","2026-11-17","2026-11-18",false,"2026-11-12"))
        assertEquals("FUTURE",PayrollDateRules.attendanceStatus("","2026-11-19","2026-11-18",true,"2026-11-12"))
    }
}

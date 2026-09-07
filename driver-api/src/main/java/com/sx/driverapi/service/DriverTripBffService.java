package com.sx.driverapi.service;
import com.sx.driverapi.client.*;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.model.trip.*;
import org.springframework.stereotype.Service;
import java.time.LocalDate;

@Service
public class DriverTripBffService {
    private final DriverTripClient client;
    public DriverTripBffService(DriverTripClient client) { this.client=client; }
    public DriverTripPage page(long driverId,String status,LocalDate start,LocalDate end,String dateField,int page,int size,Long snapshot) {
        return unwrap(client.page(Long.toString(driverId),status,start==null?null:start.toString(),end==null?null:end.toString(),dateField,page,size,snapshot));
    }
    public DriverTripView detail(long driverId,long id) { return unwrap(client.detail(Long.toString(driverId),id)); }
    public DriverTodaySummary today(long driverId) { return unwrap(client.today(Long.toString(driverId))); }
    private <T> T unwrap(CoreResponseVo<T> response) {
        if (response==null || response.getCode()==null) throw new BizErrorException(502,"行程服务暂时不可用");
        if (response.getCode()!=200) throw new BizErrorException(response.getCode(),response.getMsg());
        if (response.getData()==null) throw new BizErrorException(502,"行程服务返回为空");
        return response.getData();
    }
}

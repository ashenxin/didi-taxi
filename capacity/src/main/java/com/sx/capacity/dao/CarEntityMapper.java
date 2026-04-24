package com.sx.capacity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.capacity.model.Car;
import com.sx.capacity.model.dto.CarPageRow;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CarEntityMapper extends BaseMapper<Car> {
    Long countCarPage(String provinceCode,
                      String cityCode,
                      Long companyId,
                      Long driverId,
                      String driverPhone,
                      String carNo,
                      String brandName,
                      String rideTypeId);

    List<CarPageRow> selectCarPage(String provinceCode,
                                  String cityCode,
                                  Long companyId,
                                  Long driverId,
                                  String driverPhone,
                                  String carNo,
                                  String brandName,
                                  String rideTypeId,
                                  long offset,
                                  int limit);

    CarPageRow selectCarDetail(Long id);
}

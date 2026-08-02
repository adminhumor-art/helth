package com.sladkaya.sensor.sibionics.datahandle.ipc;

import android.os.Bundle;

interface IDataHandleGatewayService {
    Bundle transact(int operation, in Bundle request);
}

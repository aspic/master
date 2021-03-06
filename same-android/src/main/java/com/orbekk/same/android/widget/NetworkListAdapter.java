/**
 * Copyright 2012 Kjetil Ørbekk <kjetil.orbekk@gmail.com>
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
package com.orbekk.same.android.widget;

import java.util.List;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.orbekk.same.Services;
import com.orbekk.same.Services.NetworkDirectory;
import com.orbekk.same.android.R;

/**
 * This class extends ArrayAdapter incompletely.
 * 
 * Only use as follows:
 * 
 *   list.setAdapter(new NetworkListAdapter(...));
 */
public class NetworkListAdapter extends ArrayAdapter<Services.MasterState> {
    private NetworkDirectory networks;
    
    public NetworkListAdapter(Context context, int resource_id,
            Services.NetworkDirectory networks) {
        super(context, resource_id, networks.getNetworkList());
        this.networks = networks;
    }
    
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            LayoutInflater inflater = getLayoutInflater();
            convertView = inflater.inflate(R.layout.network_list_item, null);
        }
        Services.MasterState network = networks.getNetwork(position);
        if (network != null) {
            TextView nameText = (TextView)convertView.findViewById(
                    R.id.network_name_text);
            TextView urlText = (TextView)convertView.findViewById(
                    R.id.master_url_text);
            nameText.setText(network.getNetworkName());
            urlText.setText(network.getMasterLocation());
        }
        return convertView;
    }
    
    private LayoutInflater getLayoutInflater() {
        return (LayoutInflater)getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);   
    }
}
